/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package kafka.coordinator.group

import java.util.UUID

import kafka.common.OffsetAndMetadata
import kafka.utils.{Logging, nonthreadsafe}
import org.apache.kafka.common.TopicPartition

import scala.collection.{Seq, immutable, mutable}

private[group] sealed trait GroupState { def state: Byte }

/**
 * Group is preparing to rebalance
 *
 * action: respond to heartbeats with REBALANCE_IN_PROGRESS
 *         respond to sync group with REBALANCE_IN_PROGRESS
 *         remove member on leave group request
 *         park join group requests from new or existing members until all expected members have joined
 *         allow offset commits from previous generation
 *         allow offset fetch requests
 * transition: some members have joined by the timeout => AwaitingSync
 *             all members have left the group => Empty
 *             group is removed by partition emigration => Dead
 */
private[group] case object PreparingRebalance extends GroupState { val state: Byte = 1 }

/**
 * Group is awaiting state assignment from the leader
 *
 * action: respond to heartbeats with REBALANCE_IN_PROGRESS
 *         respond to offset commits with REBALANCE_IN_PROGRESS
 *         park sync group requests from followers until transition to Stable
 *         allow offset fetch requests
 * transition: sync group with state assignment received from leader => Stable
 *             join group from new member or existing member with updated metadata => PreparingRebalance
 *             leave group from existing member => PreparingRebalance
 *             member failure detected => PreparingRebalance
 *             group is removed by partition emigration => Dead
 */
private[group] case object AwaitingSync extends GroupState { val state: Byte = 5}

/**
 * Group is stable
 *
 * action: respond to member heartbeats normally
 *         respond to sync group from any member with current assignment
 *         respond to join group from followers with matching metadata with current group metadata
 *         allow offset commits from member of current generation
 *         allow offset fetch requests
 * transition: member failure detected via heartbeat => PreparingRebalance
 *             leave group from existing member => PreparingRebalance
 *             leader join-group received => PreparingRebalance
 *             follower join-group with new metadata => PreparingRebalance
 *             group is removed by partition emigration => Dead
 */
private[group] case object Stable extends GroupState { val state: Byte = 3 }

/**
 * Group has no more members and its metadata is being removed
 *
 * action: respond to join group with UNKNOWN_MEMBER_ID
 *         respond to sync group with UNKNOWN_MEMBER_ID
 *         respond to heartbeat with UNKNOWN_MEMBER_ID
 *         respond to leave group with UNKNOWN_MEMBER_ID
 *         respond to offset commit with UNKNOWN_MEMBER_ID
 *         allow offset fetch requests
 * transition: Dead is a final state before group metadata is cleaned up, so there are no transitions
 */
private[group] case object Dead extends GroupState { val state: Byte = 4 }

/**
  * Group has no more members, but lingers until all offsets have expired. This state
  * also represents groups which use Kafka only for offset commits and have no members.
  *
  * action: respond normally to join group from new members
  *         respond to sync group with UNKNOWN_MEMBER_ID
  *         respond to heartbeat with UNKNOWN_MEMBER_ID
  *         respond to leave group with UNKNOWN_MEMBER_ID
  *         respond to offset commit with UNKNOWN_MEMBER_ID
  *         allow offset fetch requests
  * transition: last offsets removed in periodic expiration task => Dead
  *             join group from a new member => PreparingRebalance
  *             group is removed by partition emigration => Dead
  *             group is removed by expiration => Dead
  */
private[group] case object Empty extends GroupState { val state: Byte = 5 }


private object GroupMetadata {
  private val validPreviousStates: Map[GroupState, Set[GroupState]] =
    Map(Dead -> Set(Stable, PreparingRebalance, AwaitingSync, Empty, Dead),
      AwaitingSync -> Set(PreparingRebalance),
      Stable -> Set(AwaitingSync),
      PreparingRebalance -> Set(Stable, AwaitingSync, Empty),
      Empty -> Set(PreparingRebalance))
}

/**
 * Case class used to represent group metadata for the ListGroups API
 */
case class GroupOverview(groupId: String,
                         protocolType: String)

/**
 * Case class used to represent group metadata for the DescribeGroup API
 */
case class GroupSummary(state: String,
                        protocolType: String,
                        protocol: String,
                        members: List[MemberSummary])

/**
 * Group contains the following metadata:
 *
 *  Membership metadata:
 *  1. Members registered in this group
 *  2. Current protocol assigned to the group (e.g. partition assignment strategy for consumers)
 *  3. Protocol metadata associated with group members
 *
 *  State metadata:
 *  1. group state
 *  2. generation id
 *  3. leader id
 */
@nonthreadsafe
private[group] class GroupMetadata(val groupId: String, initialState: GroupState = Empty) extends Logging {

  private var state: GroupState = initialState
  private val members = new mutable.HashMap[String, MemberMetadata]
  private val offsets = new mutable.HashMap[TopicPartition, OffsetAndMetadata]
  private val pendingOffsetCommits = new mutable.HashMap[TopicPartition, OffsetAndMetadata]
  // A map from a PID to the open offset commits for that pid.
  private val pendingTransactionalOffsetCommits = new mutable.HashMap[Long, mutable.Map[TopicPartition, OffsetAndMetadata]]()
  private var receivedTransactionalOffsetCommits = false
  private var receivedConsumerOffsetCommits = false

  var protocolType: Option[String] = None
  var generationId = 0
  var leaderId: String = null
  var protocol: String = null
  var newMemberAdded: Boolean = false

  def is(groupState: GroupState) = state == groupState
  def not(groupState: GroupState) = state != groupState
  def has(memberId: String) = members.contains(memberId)
  def get(memberId: String) = members(memberId)

  def add(member: MemberMetadata) {
    if (members.isEmpty)
      this.protocolType = Some(member.protocolType)

    assert(groupId == member.groupId)
    assert(this.protocolType.orNull == member.protocolType)
    assert(supportsProtocols(member.protocols))

    if (leaderId == null)
      leaderId = member.memberId
    members.put(member.memberId, member)
  }

  def remove(memberId: String) {
    members.remove(memberId)
    if (memberId == leaderId) {
      leaderId = if (members.isEmpty) {
        null
      } else {
        members.keys.head
      }
    }
  }

  def currentState = state

  def notYetRejoinedMembers = members.values.filter(_.awaitingJoinCallback == null).toList

  def allMembers = members.keySet

  def allMemberMetadata = members.values.toList

  def rebalanceTimeoutMs = members.values.foldLeft(0) { (timeout, member) =>
    timeout.max(member.rebalanceTimeoutMs)
  }

  // TODO: decide if ids should be predictable or random
  def generateMemberIdSuffix = UUID.randomUUID().toString

  def canRebalance = GroupMetadata.validPreviousStates(PreparingRebalance).contains(state)

  def transitionTo(groupState: GroupState) {
    assertValidTransition(groupState)
    state = groupState
  }

  def selectProtocol: String = {
    if (members.isEmpty)
      throw new IllegalStateException("Cannot select protocol for empty group")

    // select the protocol for this group which is supported by all members
    val candidates = candidateProtocols

    // let each member vote for one of the protocols and choose the one with the most votes
    val votes: List[(String, Int)] = allMemberMetadata
      .map(_.vote(candidates))
      .groupBy(identity)
      .mapValues(_.size)
      .toList

    votes.maxBy(_._2)._1
  }

  private def candidateProtocols = {
    // get the set of protocols that are commonly supported by all members
    allMemberMetadata
      .map(_.protocols)
      .reduceLeft((commonProtocols, protocols) => commonProtocols & protocols)
  }

  def supportsProtocols(memberProtocols: Set[String]) = {
    members.isEmpty || (memberProtocols & candidateProtocols).nonEmpty
  }

  def initNextGeneration() = {
    assert(notYetRejoinedMembers == List.empty[MemberMetadata])
    if (members.nonEmpty) {
      generationId += 1
      protocol = selectProtocol
      transitionTo(AwaitingSync)
    } else {
      generationId += 1
      protocol = null
      transitionTo(Empty)
    }
    receivedConsumerOffsetCommits = false
    receivedTransactionalOffsetCommits = false
  }

  def currentMemberMetadata: Map[String, Array[Byte]] = {
    if (is(Dead) || is(PreparingRebalance))
      throw new IllegalStateException("Cannot obtain member metadata for group in state %s".format(state))
    members.map{ case (memberId, memberMetadata) => (memberId, memberMetadata.metadata(protocol))}.toMap
  }

  def summary: GroupSummary = {
    if (is(Stable)) {
      val members = this.members.values.map { member => member.summary(protocol) }.toList
      GroupSummary(state.toString, protocolType.getOrElse(""), protocol, members)
    } else {
      val members = this.members.values.map{ member => member.summaryNoMetadata() }.toList
      GroupSummary(state.toString, protocolType.getOrElse(""), GroupCoordinator.NoProtocol, members)
    }
  }

  def overview: GroupOverview = {
    GroupOverview(groupId, protocolType.getOrElse(""))
  }

  def initializeOffsets(offsets: collection.Map[TopicPartition, OffsetAndMetadata],
                        pendingTxnOffsets: Map[Long, mutable.Map[TopicPartition, OffsetAndMetadata]]) {
    this.offsets ++= offsets
    this.pendingTransactionalOffsetCommits ++= pendingTxnOffsets
  }

  def completePendingOffsetWrite(topicPartition: TopicPartition, offset: OffsetAndMetadata) {
    if (pendingOffsetCommits.contains(topicPartition))
      offsets.put(topicPartition, offset)

    pendingOffsetCommits.get(topicPartition) match {
      case Some(stagedOffset) if offset == stagedOffset => pendingOffsetCommits.remove(topicPartition)
      case _ =>
    }
  }

  def failPendingOffsetWrite(topicPartition: TopicPartition, offset: OffsetAndMetadata): Unit = {
    pendingOffsetCommits.get(topicPartition) match {
      case Some(pendingOffset) if offset == pendingOffset => pendingOffsetCommits.remove(topicPartition)
      case _ =>
    }
  }

  def prepareOffsetCommit(offsets: Map[TopicPartition, OffsetAndMetadata]) {
    receivedConsumerOffsetCommits = true
    pendingOffsetCommits ++= offsets
  }

  def prepareTxnOffsetCommit(producerId: Long, offsets: Map[TopicPartition, OffsetAndMetadata]) {
    receivedTransactionalOffsetCommits = true
    val producerOffsets = pendingTransactionalOffsetCommits.getOrElseUpdate(producerId, mutable.Map.empty[TopicPartition, OffsetAndMetadata])
    producerOffsets ++= offsets
  }

  def hasReceivedConsistentOffsetCommits : Boolean = {
    !receivedConsumerOffsetCommits || !receivedTransactionalOffsetCommits
  }

  /* Remove a pending transactional offset commit if the actual offset commit record was not written to the log.
   * We will return an error and the client will retry the request, potentially to a different coordinator.
   */
  def failPendingTxnOffsetCommit(producerId: Long, topicPartition: TopicPartition, offsetAndMetadata: OffsetAndMetadata): Unit = {
    pendingTransactionalOffsetCommits.get(producerId) match {
      case Some(pendingOffsets) =>
        pendingOffsets.remove(topicPartition)
        if (pendingOffsets.isEmpty)
          pendingTransactionalOffsetCommits.remove(producerId)
      case _ =>
        // We may hit this case if the partition in question has emigrated already.
    }
  }

  /* Complete a pending transactional offset commit. This is called after a commit or abort marker is fully written
   * to the log.
   */
  def completePendingTxnOffsetCommit(producerId: Long, isCommit: Boolean): Unit = {
    trace(s"Completing transactional offset commit for producer $producerId and group $groupId. isCommit: $isCommit")
    if (isCommit) {
      val producerOffsets = pendingTransactionalOffsetCommits.getOrElse(producerId, Map.empty[TopicPartition, OffsetAndMetadata])
      offsets ++= producerOffsets
    }
    pendingTransactionalOffsetCommits.remove(producerId)
  }

  def activeProducers = pendingTransactionalOffsetCommits.keySet

  def hasPendingOffsetCommitsFromProducer(producerId: Long) =
    pendingTransactionalOffsetCommits.contains(producerId)

  def removeOffsets(topicPartitions: Seq[TopicPartition]): immutable.Map[TopicPartition, OffsetAndMetadata] = {
    topicPartitions.flatMap { topicPartition =>
      pendingOffsetCommits.remove(topicPartition)
      pendingTransactionalOffsetCommits.foreach { case (_, pendingOffsets) =>
        pendingOffsets.remove(topicPartition)
      }
      val removedOffset = offsets.remove(topicPartition)
      removedOffset.map(topicPartition -> _)
    }.toMap
  }

  def removeExpiredOffsets(startMs: Long) = {
    val expiredOffsets = offsets.filter {
      case (topicPartition, offset) => offset.expireTimestamp < startMs && !pendingOffsetCommits.contains(topicPartition)
    }
    offsets --= expiredOffsets.keySet
    expiredOffsets.toMap
  }

  def allOffsets = offsets.toMap

  def offset(topicPartition: TopicPartition) = offsets.get(topicPartition)

  def numOffsets = offsets.size

  def hasOffsets = offsets.nonEmpty || pendingOffsetCommits.nonEmpty || pendingTransactionalOffsetCommits.nonEmpty

  private def assertValidTransition(targetState: GroupState) {
    if (!GroupMetadata.validPreviousStates(targetState).contains(state))
      throw new IllegalStateException("Group %s should be in the %s states before moving to %s state. Instead it is in %s state"
        .format(groupId, GroupMetadata.validPreviousStates(targetState).mkString(","), targetState, state))
  }

  override def toString: String = {
    "[%s,%s,%s,%s]".format(groupId, protocolType, currentState.toString, members)
  }
}

