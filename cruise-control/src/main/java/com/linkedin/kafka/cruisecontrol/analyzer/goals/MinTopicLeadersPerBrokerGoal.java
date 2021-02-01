/*
 * Copyright 2021 LinkedIn Corp. Licensed under the BSD 2-Clause License (the "License"). See License in the project root for license information.
 */

package com.linkedin.kafka.cruisecontrol.analyzer.goals;

import com.linkedin.kafka.cruisecontrol.analyzer.ActionAcceptance;
import com.linkedin.kafka.cruisecontrol.analyzer.ActionType;
import com.linkedin.kafka.cruisecontrol.analyzer.BalancingAction;
import com.linkedin.kafka.cruisecontrol.analyzer.BalancingConstraint;
import com.linkedin.kafka.cruisecontrol.analyzer.OptimizationOptions;
import com.linkedin.kafka.cruisecontrol.common.Utils;
import com.linkedin.kafka.cruisecontrol.config.constants.AnalyzerConfig;
import com.linkedin.kafka.cruisecontrol.exception.OptimizationFailureException;
import com.linkedin.kafka.cruisecontrol.model.Broker;
import com.linkedin.kafka.cruisecontrol.model.ClusterModel;
import com.linkedin.kafka.cruisecontrol.model.Replica;
import com.linkedin.kafka.cruisecontrol.model.ReplicaSortFunctionFactory;
import com.linkedin.kafka.cruisecontrol.model.SortedReplicasHelper;
import com.linkedin.kafka.cruisecontrol.monitor.ModelCompletenessRequirements;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static com.linkedin.kafka.cruisecontrol.analyzer.ActionAcceptance.REPLICA_REJECT;
import static com.linkedin.kafka.cruisecontrol.analyzer.ActionAcceptance.ACCEPT;
import static com.linkedin.kafka.cruisecontrol.analyzer.ActionType.LEADERSHIP_MOVEMENT;
import static com.linkedin.kafka.cruisecontrol.analyzer.ActionType.INTER_BROKER_REPLICA_MOVEMENT;
import static com.linkedin.kafka.cruisecontrol.analyzer.goals.GoalUtils.replicaSortName;


/**
 * HARD GOAL: Generate leadership movement and leader replica movement proposals to ensure that each alive broker that
 * is not excluded for replica leadership moves has at least the minimum number (specified by
 * {@link AnalyzerConfig#MIN_TOPIC_LEADERS_PER_BROKER_CONFIG}) of leader replica of each topic in a configured set of topics
 * (specified by {@link AnalyzerConfig#TOPICS_WITH_MIN_LEADERS_PER_BROKER_CONFIG}).
 */
public class MinTopicLeadersPerBrokerGoal extends AbstractGoal {
  private static final Logger LOG = LoggerFactory.getLogger(MinTopicLeadersPerBrokerGoal.class);
  private Set<String> _mustHaveTopicLeadersPerBroker;

  public MinTopicLeadersPerBrokerGoal() {

  }

  /**
   * Package private for unit test.
   */
  MinTopicLeadersPerBrokerGoal(BalancingConstraint constraint) {
    _balancingConstraint = constraint;
  }

  @Override
  public ClusterModelStatsComparator clusterModelStatsComparator() {
    return new GoalUtils.HardGoalStatsComparator();
  }

  @Override
  public ModelCompletenessRequirements clusterModelCompletenessRequirements() {
    return new ModelCompletenessRequirements(GoalUtils.MIN_NUM_VALID_WINDOWS_FOR_SELF_HEALING, 0.0, true);
  }

  @Override
  public String name() {
    return MinTopicLeadersPerBrokerGoal.class.getSimpleName();
  }

  @Override
  public boolean isHardGoal() {
    return true;
  }

  /**
   * Check whether given action is acceptable by this goal. An action is acceptable by a goal if it satisfies
   * requirements of the goal. Requirements(hard goal): A configured set of topics that should have at least a minimum number
   * of leader replicas on each alive broker that is not excluded for replica leadership moves
   *
   * @param action Action to be checked for acceptance.
   * @param clusterModel The state of the cluster.
   * @return {@link ActionAcceptance#ACCEPT} if the action is acceptable by this goal,
   *         {@link ActionAcceptance#REPLICA_REJECT} otherwise.
   */
  @Override
  public ActionAcceptance actionAcceptance(BalancingAction action, ClusterModel clusterModel) {
    if (!actionAffectsRelevantTopics(action)) {
      return ACCEPT;
    }
    switch (action.balancingAction()) {
      case LEADERSHIP_MOVEMENT:
      case INTER_BROKER_REPLICA_MOVEMENT:
        Replica replicaToBeRemoved = clusterModel.broker(action.sourceBrokerId()).replica(action.topicPartition());
        return doesLeaderReplicaRemoveViolateOptimizedGoal(replicaToBeRemoved) ? REPLICA_REJECT : ACCEPT;
      case INTER_BROKER_REPLICA_SWAP:
        Replica srcReplicaToSwap = clusterModel.broker(action.sourceBrokerId()).replica(action.topicPartition());
        Replica dstReplicaToSwap = clusterModel.broker(action.destinationBrokerId()).replica(action.destinationTopicPartition());
        return acceptReplicaSwap(srcReplicaToSwap, dstReplicaToSwap);
      default:
        throw new IllegalArgumentException("Unsupported balancing action " + action.balancingAction() + " is provided.");
    }
  }

  private ActionAcceptance acceptReplicaSwap(Replica srcReplicaToSwap, Replica dstReplicaToSwap) {
    if (!srcReplicaToSwap.isLeader() && !dstReplicaToSwap.isLeader()) {
      // Swapping two follower replicas is always accepted
      return ACCEPT;
    }
    String srcTopicName = srcReplicaToSwap.topicPartition().topic();
    String dstTopicName = dstReplicaToSwap.topicPartition().topic();
    if (srcReplicaToSwap.isLeader() && dstReplicaToSwap.isLeader() && Objects.equals(srcTopicName, dstTopicName)) {
      // Swapping two leader replicas of the same topic is always accepted
      return ACCEPT;
    }
    if (doesLeaderReplicaRemoveViolateOptimizedGoal(srcReplicaToSwap) || doesLeaderReplicaRemoveViolateOptimizedGoal(dstReplicaToSwap)) {
      return REPLICA_REJECT;
    }
    return ACCEPT;
  }

  /**
   * Check whether this goal is violated after removing the given leader replica. It is violated if there is not be enough
   * leader replicas on that broker for that topic
   *
   * @param replicaToBeRemoved replica to be removed
   * @return {@code true} if the given replica move would violate the optimized goal (i.e. the move is not acceptable),
   * {@code false} otherwise.
   */
  private boolean doesLeaderReplicaRemoveViolateOptimizedGoal(Replica replicaToBeRemoved) {
    if (!replicaToBeRemoved.isLeader()) {
      // Moving a follower replica does not violate/affect this goal
      return false;
    }
    String topicName = replicaToBeRemoved.topicPartition().topic();
    if (!_mustHaveTopicLeadersPerBroker.contains(topicName)) {
      // Moving a replica of a irrelevant topic does not violate/affect this goal
      return false;
    }
    int topicLeaderReplicaCountOnSourceBroker = replicaToBeRemoved.broker().numLeaderReplicasOfTopicInBroker(topicName);
    return topicLeaderReplicaCountOnSourceBroker <= minTopicLeadersPerBroker();
  }

  /**
   * This is a hard goal; hence, the proposals are not limited to dead broker replicas in case of self-healing.
   * Sanity Check: The total number of leader replicas of certain topics is sufficient so that these leader replicas
   * can be moved to satisfy this goal.
   *
   * @param clusterModel The state of the cluster.
   * @param optimizationOptions Options to take into account during optimization.
   */
  @Override
  protected void initGoalState(ClusterModel clusterModel, OptimizationOptions optimizationOptions)
      throws OptimizationFailureException {
    _mustHaveTopicLeadersPerBroker = Collections.unmodifiableSet(
        Utils.getTopicNamesMatchedWithPattern(_balancingConstraint.topicsWithMinLeadersPerBrokerPattern(), clusterModel::topics));
    if (_mustHaveTopicLeadersPerBroker.isEmpty()) {
      return;
    }
    // Sanity checks
    validateTopicsWithMinLeaderReplicaIsNotExcluded(optimizationOptions);
    validateEnoughLeaderReplicaToDistribute(clusterModel, optimizationOptions);
    validateBrokersAllowedReplicaMoveExist(clusterModel, optimizationOptions);
    boolean onlyMoveImmigrantReplicas = optimizationOptions.onlyMoveImmigrantReplicas();
    new SortedReplicasHelper().addSelectionFunc(ReplicaSortFunctionFactory.selectLeaders())
                              .maybeAddSelectionFunc(ReplicaSortFunctionFactory.selectImmigrants(), onlyMoveImmigrantReplicas)
                              .addSelectionFunc(ReplicaSortFunctionFactory.selectReplicasBasedOnIncludedTopics(_mustHaveTopicLeadersPerBroker))
                              .maybeAddPriorityFunc(ReplicaSortFunctionFactory.prioritizeImmigrants(), !onlyMoveImmigrantReplicas)
                              .trackSortedReplicasFor(replicaSortName(this, true, true), clusterModel);
  }

  private int minTopicLeadersPerBroker() {
    return _balancingConstraint.minTopicLeadersPerBroker();
  }

  private void validateTopicsWithMinLeaderReplicaIsNotExcluded(OptimizationOptions optimizationOptions)
      throws OptimizationFailureException {
    if (optimizationOptions.excludedTopics().isEmpty()) {
      return;
    }
    Set<String> shouldNotBeExcludedTopics = new HashSet<>();
    _mustHaveTopicLeadersPerBroker.forEach(topicName -> {
      if (optimizationOptions.excludedTopics().contains(topicName)) {
        shouldNotBeExcludedTopics.add(topicName);
      }
    });
    if (!shouldNotBeExcludedTopics.isEmpty()) {
      throw new OptimizationFailureException(String.format("Topics that must have a minimum number of leader replicas "
          + "per alive broker that is not excluded for leadership moves should not be excluded. This error implies "
          + "a config error. Topics should not be excluded=[%s] and it is defined as a regex by the config property: %s",
                                                           String.join(", ", shouldNotBeExcludedTopics),
                                                           AnalyzerConfig.TOPICS_WITH_MIN_LEADERS_PER_BROKER_CONFIG));
    }
  }

  private void validateEnoughLeaderReplicaToDistribute(ClusterModel clusterModel,
                                                       OptimizationOptions optimizationOptions) throws OptimizationFailureException {
    Map<String, Integer> numLeadersByTopicNames = clusterModel.numLeadersPerTopic(_mustHaveTopicLeadersPerBroker);
    Set<Broker> eligibleBrokersForLeadership = eligibleBrokersForLeadership(clusterModel, optimizationOptions);
    int totalMinimumLeaderReplicaCount = eligibleBrokersForLeadership.size() * minTopicLeadersPerBroker();

    for (Map.Entry<String, Integer> numLeadersPerTopic : numLeadersByTopicNames.entrySet()) {
      if (numLeadersPerTopic.getValue() < totalMinimumLeaderReplicaCount) {
        throw new OptimizationFailureException(
            String.format("Cannot distribute %d leader replica(s) over %d broker(s) with total minimum required leader count %d for topic %s.",
                          numLeadersPerTopic.getValue(), eligibleBrokersForLeadership.size(),
                          numLeadersPerTopic.getValue(), numLeadersPerTopic.getKey()));
      }
    }
  }

  private void validateBrokersAllowedReplicaMoveExist(ClusterModel clusterModel,
                                                      OptimizationOptions optimizationOptions) throws OptimizationFailureException {
    Set<Integer> brokersAllowedReplicaMove = GoalUtils.aliveBrokersNotExcludedForReplicaMove(clusterModel, optimizationOptions);
    if (brokersAllowedReplicaMove.isEmpty()) {
      // Handle the case when all alive brokers are excluded from replica moves.
      throw new OptimizationFailureException("Cannot take any action as all alive brokers are excluded from replica moves.");
    }
  }

  /**
   * Check if requirements of this goal are not violated if this action is applied to the given cluster state,
   * false otherwise.
   *
   * @param clusterModel The state of the cluster.
   * @param action Action containing information about potential modification to the given cluster model.
   * @return True if requirements of this goal are not violated if this action is applied to the given cluster state,
   * false otherwise.
   */
  @Override
  protected boolean selfSatisfied(ClusterModel clusterModel, BalancingAction action) {
    if (!actionAffectsRelevantTopics(action)) {
      return true;
    }
    switch (action.balancingAction()) {
      case LEADERSHIP_MOVEMENT:
      case INTER_BROKER_REPLICA_MOVEMENT:
        Broker sourceBroker = clusterModel.broker(action.sourceBrokerId());
        Replica replicaToBeMoved = sourceBroker.replica(action.topicPartition());
        if (!replicaToBeMoved.isLeader()) {
          return true; // Move a follower replica of a topic of this goal's interest is always self-satisfied since it does not matter
        }
        return isSelfSatisfiedToMoveOneLeaderReplica(sourceBroker,
                                                     clusterModel.broker(action.destinationBrokerId()),
                                                     replicaToBeMoved);
      case INTER_BROKER_REPLICA_SWAP:
        return isSwapActionSelfSatisfied(clusterModel, action);
      default:
        throw new IllegalArgumentException("Unsupported balancing action " + action.balancingAction() + " is provided.");
    }
  }

  private boolean isSwapActionSelfSatisfied(ClusterModel clusterModel, BalancingAction action) {
    if (action.balancingAction() != ActionType.INTER_BROKER_REPLICA_SWAP) {
      throw new IllegalArgumentException("Expected INTER_BROKER_REPLICA_SWAP. But got: " + action.balancingAction());
    }
    Replica sourceReplica = clusterModel.broker(action.sourceBrokerId()).replica(action.topicPartition());
    Replica destinationReplica = clusterModel.broker(action.destinationBrokerId()).replica(action.topicPartition());
    if (sourceReplica.isLeader() == destinationReplica.isLeader() && Objects.equals(action.topic(), action.destinationTopic())) {
      // Swapping either 2 leader replicas or 2 follower replicas of the same topic has no effect on this goal
      return true;
    }
    return isSelfSatisfiedToMoveOneLeaderReplica(clusterModel.broker(action.sourceBrokerId()),
                                                 clusterModel.broker(action.destinationBrokerId()),
                                                 sourceReplica)
           && isSelfSatisfiedToMoveOneLeaderReplica(clusterModel.broker(action.destinationBrokerId()),
                                                    clusterModel.broker(action.sourceBrokerId()),
                                                    destinationReplica);
  }

  private boolean actionAffectsRelevantTopics(BalancingAction action) {
    if (_mustHaveTopicLeadersPerBroker.contains(action.topic())) {
      return true;
    }
    return action.balancingAction() == ActionType.INTER_BROKER_REPLICA_SWAP &&
           _mustHaveTopicLeadersPerBroker.contains(action.destinationTopic());
  }

  /**
   * Check whether it is self-satisfied to move a leader replica of a topic with the given name from a the given source
   * broker to the given destination broker
   * @param sourceBroker the broker from which a leader replica is removed
   * @param destinationBroker the broker from which a leader replica is added
   * @param replicaToBeMoved replica to be moved from the source broker to the destination broker
   * @return {@code true} if the given replica move would violate the optimized goal (i.e. the move is not acceptable),
   * {@code false} otherwise.
   */
  private boolean isSelfSatisfiedToMoveOneLeaderReplica(Broker sourceBroker, Broker destinationBroker, Replica replicaToBeMoved) {
    if (!replicaToBeMoved.isLeader()) {
      return true;
    }
    String topicName = replicaToBeMoved.topicPartition().topic();
    if (!_mustHaveTopicLeadersPerBroker.contains(topicName)) {
      return true;
    }
    // Moving a replica leadership from a not-alive broker to an alive broker is self-satisfied
    if (!sourceBroker.isAlive() && destinationBroker.isAlive()) {
      return true;
    }
    // Moving leader replica from more abundant broker could be considered as self-satisfied
    return sourceBroker.numLeaderReplicasOfTopicInBroker(topicName) > destinationBroker.numLeaderReplicasOfTopicInBroker(topicName);
  }

  /**
   * Update goal state after one round of self-healing / rebalance.
   *
   *  @param clusterModel The state of the cluster.
   * @param optimizationOptions Options to take into account during optimization.
   */
  @Override
  protected void updateGoalState(ClusterModel clusterModel, OptimizationOptions optimizationOptions)
      throws OptimizationFailureException {
    // Sanity check: No self-healing eligible replica should remain at a dead broker/disk.
    GoalUtils.ensureNoOfflineReplicas(clusterModel, name());
    // Sanity check: No replica should be moved to a broker, which used to host any replica of the same partition on its broken disk.
    GoalUtils.ensureReplicasMoveOffBrokersWithBadDisks(clusterModel, name());
    ensureBrokersAllHaveEnoughLeaderReplicaOfTopics(clusterModel, optimizationOptions);
    finish();
  }

  private void ensureBrokersAllHaveEnoughLeaderReplicaOfTopics(ClusterModel clusterModel, OptimizationOptions optimizationOptions)
      throws OptimizationFailureException {
    if (_mustHaveTopicLeadersPerBroker.isEmpty()) {
      return; // Early termination to avoid some unnecessary computation
    }
    for (Broker broker : clusterModel.brokers()) {
      if (!isEligibleToHaveLeaders(broker, optimizationOptions)) {
        continue;
      }
      for (String mustHaveLeaderReplicaPerBrokerTopicName : _mustHaveTopicLeadersPerBroker) {
        int leaderReplicaCount = broker.numLeaderReplicasOfTopicInBroker(mustHaveLeaderReplicaPerBrokerTopicName);
        if (leaderReplicaCount < minTopicLeadersPerBroker()) {
          throw new OptimizationFailureException(String.format("Broker %d does not have enough leader replica for topic %s. "
              + "Minimum required per-broker leader replica count %d. Actual broker leader replica count %d",
              broker.id(), mustHaveLeaderReplicaPerBrokerTopicName, minTopicLeadersPerBroker(), leaderReplicaCount));
        }
      }
    }
  }

  /**
   * Rebalance the given broker without violating the constraints of the current goal and optimized goals.
   * @param broker         Broker to be balanced.
   * @param clusterModel   The state of the cluster.
   * @param optimizedGoals Optimized goals.
   * @param optimizationOptions Options to take into account during optimization.
   */
  @Override
  protected void rebalanceForBroker(Broker broker,
                                    ClusterModel clusterModel,
                                    Set<Goal> optimizedGoals,
                                    OptimizationOptions optimizationOptions) throws OptimizationFailureException {
    LOG.debug("balancing broker {}, optimized goals = {}", broker, optimizedGoals);
    moveAwayOfflineReplicas(broker, clusterModel, optimizedGoals, optimizationOptions);
    if (_mustHaveTopicLeadersPerBroker.isEmpty()) {
      return; // Early termination to avoid some unnecessary computation
    }
    if (!isEligibleToHaveLeaders(broker, optimizationOptions)) {
      return;
    }
    for (String topicMustHaveLeaderReplicaPerBroker : _mustHaveTopicLeadersPerBroker) {
      maybeMoveLeaderReplicaOfTopicToBroker(topicMustHaveLeaderReplicaPerBroker, broker, clusterModel, optimizedGoals, optimizationOptions);
    }
  }

  private void maybeMoveLeaderReplicaOfTopicToBroker(String topicMustHaveLeaderReplicaPerBroker,
                                                     Broker broker,
                                                     ClusterModel clusterModel,
                                                     Set<Goal> optimizedGoals,
                                                     OptimizationOptions optimizationOptions) throws OptimizationFailureException {
    if (brokerHasSufficientLeaderReplicasOfTopic(broker, topicMustHaveLeaderReplicaPerBroker)) {
      return; // This broker has enough leader replica(s)
    }
    // Try to elect follower replica(s) of the interested topic on this broker to be leader
    Set<Replica> followerReplicas = broker.replicas().stream()
                                          .filter(
                                              replica -> !replica.isLeader()
                                                         && replica.topicPartition().topic().equals(topicMustHaveLeaderReplicaPerBroker))
                                          .collect(Collectors.toSet());

    for (Replica followerReplica : followerReplicas) {
      Replica leaderReplica = leaderReplicaOfTopicPartition(clusterModel, followerReplica.topicPartition());
      if (leaderReplica == null) {
        LOG.warn("No leader replica for {}", followerReplica.topicPartition());
      } else {
        if (leaderReplica.broker().id() != broker.id()
            && leaderReplica.broker().numLeaderReplicasOfTopicInBroker(topicMustHaveLeaderReplicaPerBroker) > minTopicLeadersPerBroker()) {
          maybeApplyBalancingAction(clusterModel, leaderReplica, Collections.singleton(broker),
                                    LEADERSHIP_MOVEMENT, optimizedGoals, optimizationOptions);
        }
      }
      if (brokerHasSufficientLeaderReplicasOfTopic(broker, topicMustHaveLeaderReplicaPerBroker)) {
        return; // This broker satisfies this goal
      }
    }

    // Try to move leader replica(s) of the interested topic from other brokers to this broker
    PriorityQueue<Broker> brokersWithExcessiveLeaderReplicaToMove =
        getBrokersWithExcessiveLeaderReplicaToMove(topicMustHaveLeaderReplicaPerBroker, clusterModel, broker);

    while (!brokersWithExcessiveLeaderReplicaToMove.isEmpty()) {
      Broker brokerWithExcessiveLeaderReplicaToMove = brokersWithExcessiveLeaderReplicaToMove.poll();
      List<Replica> leaderReplicasOfTopic = brokerWithExcessiveLeaderReplicaToMove.replicas().stream()
                                                                                  .filter(replica ->
                                                                                              replica.isLeader()
                                                                                              && replica.topicPartition()
                                                                                                        .topic()
                                                                                                        .equals(topicMustHaveLeaderReplicaPerBroker))
                                                                                  .collect(Collectors.toList());
      boolean leaderReplicaMoved = false;
      for (Replica leaderReplicaOfTopic : leaderReplicasOfTopic) {
        Broker destinationBroker = maybeApplyBalancingAction(clusterModel, leaderReplicaOfTopic, Collections.singleton(broker),
                                                             INTER_BROKER_REPLICA_MOVEMENT, optimizedGoals, optimizationOptions);
        if (destinationBroker != null) {
          leaderReplicaMoved = true;
          break; // Successfully move one leader replica
        }
      }
      if (leaderReplicaMoved) {
        if (brokerHasSufficientLeaderReplicasOfTopic(broker, topicMustHaveLeaderReplicaPerBroker)) {
          return; // This broker satisfies this goal
        }
        if (brokerHasExcessiveLeaderReplicasOfTopic(topicMustHaveLeaderReplicaPerBroker, brokerWithExcessiveLeaderReplicaToMove)) {
          brokersWithExcessiveLeaderReplicaToMove.add(brokerWithExcessiveLeaderReplicaToMove);
        }
      }
    }

    if (!brokerHasSufficientLeaderReplicasOfTopic(broker, topicMustHaveLeaderReplicaPerBroker)) {
      throw new OptimizationFailureException(String.format("Cannot make broker %d have at least %d leader replica(s) of topic %s",
                                                           broker.id(),
                                                           minTopicLeadersPerBroker(),
                                                           topicMustHaveLeaderReplicaPerBroker));
    }
  }

  private boolean brokerHasSufficientLeaderReplicasOfTopic(Broker broker, String topicName) {
    return broker.numLeaderReplicasOfTopicInBroker(topicName) >= minTopicLeadersPerBroker();
  }

  /**
   * This method creates a priority queue which has the broker with the most number of leader replicas of a given topic at the top of queue
   * @param topicName name of the given topic
   * @param clusterModel cluster model
   * @param originalBroker original broker which needs to be excluded from the priority queue
   * @return a priority queue which has the broker with the most number of leader replicas of a given topic at the top of queue
   */
  private PriorityQueue<Broker> getBrokersWithExcessiveLeaderReplicaToMove(String topicName, ClusterModel clusterModel, Broker originalBroker) {
    PriorityQueue<Broker> brokersWithExcessiveLeaderReplicaToMove = new PriorityQueue<>((broker1, broker2) -> {
      int broker1LeaderReplicaCount = broker1.numLeaderReplicasOfTopicInBroker(topicName);
      int broker2LeaderReplicaCount = broker2.numLeaderReplicasOfTopicInBroker(topicName);
      int leaderReplicaCountCompareResult = Integer.compare(broker2LeaderReplicaCount, broker1LeaderReplicaCount);
      return leaderReplicaCountCompareResult == 0 ? Integer.compare(broker1.id(), broker2.id()) : leaderReplicaCountCompareResult;
    });
    clusterModel.brokers().forEach((broker -> {
      if (broker.isAlive() && broker.id() != originalBroker.id() && brokerHasExcessiveLeaderReplicasOfTopic(topicName, broker)) {
        brokersWithExcessiveLeaderReplicaToMove.add(broker);
      }
    }));
    return brokersWithExcessiveLeaderReplicaToMove;
  }

  private boolean brokerHasExcessiveLeaderReplicasOfTopic(String topicName, Broker broker) {
    return broker.numLeaderReplicasOfTopicInBroker(topicName) > minTopicLeadersPerBroker();
  }

  @Nullable
  private Replica leaderReplicaOfTopicPartition(ClusterModel clusterModel, TopicPartition topicPartition) {
    for (Replica leaderReplica : clusterModel.leaderReplicas()) {
      if (leaderReplica.topicPartition().equals(topicPartition)) {
        return leaderReplica;
      }
    }
    return null;
  }


  private Set<Broker> eligibleBrokersForLeadership(ClusterModel clusterModel, OptimizationOptions optimizationOptions) {
    return clusterModel.brokers()
                       .stream()
                       .filter(broker -> isEligibleToHaveLeaders(broker, optimizationOptions))
                       .collect(Collectors.toSet());
  }

  private boolean isEligibleToHaveLeaders(Broker broker, OptimizationOptions optimizationOptions) {
    return broker.isAlive()
           && !optimizationOptions.excludedBrokersForLeadership().contains(broker.id())
           && !optimizationOptions.excludedBrokersForReplicaMove().contains(broker.id());
  }

  private void moveAwayOfflineReplicas(Broker srcBroker,
                                       ClusterModel clusterModel,
                                       Set<Goal> optimizedGoals,
                                       OptimizationOptions optimizationOptions) throws OptimizationFailureException {
    if (srcBroker.currentOfflineReplicas().isEmpty()) {
      return;
    }
    SortedSet<Broker> eligibleBrokersToMoveOfflineReplicasTo = findEligibleBrokersToMoveOfflineReplicasTo(clusterModel, optimizationOptions);
    eligibleBrokersToMoveOfflineReplicasTo.removeIf(broker -> broker.id() == srcBroker.id());
    if (eligibleBrokersToMoveOfflineReplicasTo.isEmpty()) {
      throw new OptimizationFailureException("Cannot move all replicas away from broker " + srcBroker);
    }
    Set<Replica> offlineReplicas = new HashSet<>(srcBroker.currentOfflineReplicas());
    for (Replica offlineReplica : offlineReplicas) {
      if (maybeApplyBalancingAction(clusterModel, offlineReplica, eligibleBrokersToMoveOfflineReplicasTo,
                                    INTER_BROKER_REPLICA_MOVEMENT, optimizedGoals, optimizationOptions) == null) {
        throw new OptimizationFailureException(String.format("Cannot move offline replica %s to any broker in %s",
                                                             offlineReplica, eligibleBrokersToMoveOfflineReplicasTo));
      }
    }
  }

  private SortedSet<Broker> findEligibleBrokersToMoveOfflineReplicasTo(ClusterModel clusterModel, OptimizationOptions optimizationOptions) {
    Set<Broker> candidateBrokers = clusterModel.brokers()
                                               .stream()
                                               .filter(broker -> broker.isAlive()
                                                                 && !optimizationOptions.excludedBrokersForReplicaMove().contains(broker.id()))
                                               .collect(Collectors.toSet());

    SortedSet<Broker> sortedBrokers = new TreeSet<>(
        Comparator.comparingInt((Broker broker) -> broker.replicas().size()).thenComparingInt(Broker::id));
    sortedBrokers.addAll(candidateBrokers);
    return sortedBrokers;
  }

  @Override
  public void finish() {
    super.finish();
  }
}
