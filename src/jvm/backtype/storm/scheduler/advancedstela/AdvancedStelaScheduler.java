package backtype.storm.scheduler.advancedstela;

import backtype.storm.generated.ExecutorSummary;
import backtype.storm.scheduler.*;
import backtype.storm.scheduler.advancedstela.etp.*;
import backtype.storm.scheduler.advancedstela.slo.Observer;
import backtype.storm.scheduler.advancedstela.slo.TopologyPairs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class AdvancedStelaScheduler implements IScheduler {
    private static final Logger LOG = LoggerFactory.getLogger(AdvancedStelaScheduler.class);
    private static final Integer OBSERVER_RUN_INTERVAL = 30;

    @SuppressWarnings("rawtypes")
    private Map config;
    private Observer sloObserver;
    private GlobalState globalState;
    private GlobalStatistics globalStatistics;
    private Selector selector;
    private HashMap<String, ExecutorPair> victims;
    private HashMap<String, ExecutorPair> targets;

    public void prepare(@SuppressWarnings("rawtypes") Map conf) {
        config = conf;
        sloObserver = new Observer(conf);
        globalState = new GlobalState(conf);
        globalStatistics = new GlobalStatistics(conf);
        selector = new Selector();
        victims = new HashMap<String, ExecutorPair>();
        targets = new HashMap<String, ExecutorPair>();

//  TODO: Code for running the observer as a separate thread.
//        Integer observerRunDelay = (Integer) config.get(Config.STELA_SLO_OBSERVER_INTERVAL);
//        if (observerRunDelay == null) {
//            observerRunDelay = OBSERVER_RUN_INTERVAL;
//        }
//
//        ScheduledExecutorService executorService = Executors.newSingleThreadScheduledExecutor();
//        executorService.scheduleAtFixedRate(new Runner(sloObserver), 180, observerRunDelay, TimeUnit.SECONDS);
    }

    public void schedule(Topologies topologies, Cluster cluster) {
        if (cluster.needsSchedulingTopologies(topologies).size() > 0) {

            if (!targets.isEmpty()) {
                decideAssignmentForTargets(topologies, cluster);
            }

            if (!victims.isEmpty()) {

            }

            new backtype.storm.scheduler.EvenScheduler().schedule(topologies, cluster);
        } else if (cluster.needsSchedulingTopologies(topologies).size() == 0 && topologies.getTopologies().size() > 0){

            TopologyPairs topologiesToBeRescaled = sloObserver.getTopologiesToBeRescaled();
            ArrayList<String> receivers = topologiesToBeRescaled.getReceivers();
            ArrayList<String> givers = topologiesToBeRescaled.getGivers();

            removeAlreadySelectedPairs(receivers, givers);

            if (receivers.size() > 0 && givers.size() > 0) {
                TopologyDetails target = topologies.getById(receivers.get(0));
                TopologySchedule targetSchedule = globalState.getTopologySchedules().get(receivers.get(0));
                TopologyDetails victim = topologies.getById(givers.get(givers.size() - 1));
                TopologySchedule victimSchedule = globalState.getTopologySchedules().get(givers.get(givers.size() - 1));

                ExecutorPair executorSummaries =
                        selector.selectPair(globalState, globalStatistics, receivers.get(0), givers.get(givers.size() - 1));

                if (executorSummaries.bothPopulated()) {
                    rebalanceTwoTopologies(target, targetSchedule, victim, victimSchedule, executorSummaries);
                } else {
                    LOG.error("No topology is satisfying its SLO. New nodes need to be added to the cluster");
                }
            } else if (givers.size() == 0) {
                LOG.error("No topology is satisfying its SLO. New nodes need to be added to the cluster");
            }
        }

        runAdvancedStelaComponents(cluster, topologies);
    }

    private void decideAssignmentForTargets(Topologies topologies, Cluster cluster) {
        List<TopologyDetails> unscheduledTopologies = cluster.needsSchedulingTopologies(topologies);
        for (TopologyDetails topologyDetails: unscheduledTopologies) {
            if (targets.containsKey(topologyDetails.getId())) {
                findAssignmentForTarget(cluster, topologyDetails.getId());
            }
        }
    }

    private void findAssignmentForTarget(Cluster cluster, String topologyId) {
        TopologySchedule topologySchedule = globalState.getTopologySchedules().get(topologyId);
        ExecutorPair executorPair = targets.get(topologyId);
        SchedulerAssignment currentTargetAssignment = cluster.getAssignmentById(topologyId);

        Set<ExecutorDetails> previousExecutors = topologySchedule.getExecutorToComponent().keySet();

        LOG.info("********************************************");
        LOG.info("********************* CURRENT EXECUTORS ****************");
        if (currentTargetAssignment != null) {
            Set<ExecutorDetails> currentExecutors = currentTargetAssignment.getExecutors();
            if (currentExecutors != null) {
                for (ExecutorDetails executorDetails: currentExecutors) {
                    LOG.info(executorDetails.toString());
                }
                currentExecutors.removeAll(previousExecutors);
            }
        }

        LOG.info("********************************************");
        LOG.info("********************* PREVIOUS EXECUTORS ****************");
        for (ExecutorDetails executorDetails: previousExecutors) {
            LOG.info(executorDetails.toString());
        }
        LOG.info("********************************************");
    }

    private void runAdvancedStelaComponents(Cluster cluster, Topologies topologies) {
        sloObserver.run();
        globalState.collect(cluster, topologies);
        globalStatistics.collect();
    }

    private void logUnassignedExecutors(List<TopologyDetails> topologiesScheduled, Cluster cluster) {
        for (TopologyDetails topologyDetails: topologiesScheduled) {
            Collection<ExecutorDetails> unassignedExecutors = cluster.getUnassignedExecutors(topologyDetails);
            LOG.info("******** Topology Assignment {} *********", topologyDetails.getId());
            LOG.info("Unassigned executors size: {}", unassignedExecutors.size());
            if (unassignedExecutors.size() > 0) {
                for (ExecutorDetails executorDetails: unassignedExecutors) {
                    LOG.info(executorDetails.toString());
                }
            }
        }
    }

    private void rebalanceTwoTopologies(TopologyDetails targetDetails, TopologySchedule target,
                                        TopologyDetails victimDetails, TopologySchedule victim, ExecutorPair executorSummaries) {
        String targetComponent = executorSummaries.getTargetExecutorSummary().get_component_id();
        String targetCommand = "/Users/sharanyabathey/courses/mcs-fall2015/individual-study/multitenant-stela/runs/nimbus/apache-storm-0.10.1-SNAPSHOT/bin/storm " +
                "rebalance " + targetDetails.getName() + " -e " +
                targetComponent + "=" + (target.getComponents().get(targetComponent).getParallelism() + 1);

        String victimComponent = executorSummaries.getVictimExecutorSummary().get_component_id();
        String victimCommand = "/Users/sharanyabathey/courses/mcs-fall2015/individual-study/multitenant-stela/runs/nimbus/apache-storm-0.10.1-SNAPSHOT/bin/storm " +
                "rebalance " + victimDetails.getName() + " -e " +
                victimComponent + "=" + (victim.getComponents().get(victimComponent).getParallelism() - 1);

        try {

            LOG.info("Triggering rebalance for target: {}, victim: {}\n", targetDetails.getId(), victimDetails.getId());
            LOG.info(targetCommand + "\n");
            LOG.info(victimCommand + "\n");

            Runtime.getRuntime().exec(targetCommand);
            Runtime.getRuntime().exec(victimCommand);

            targets.put(target.getId(), executorSummaries);
            victims.put(victim.getId(), executorSummaries);

            sloObserver.clearTopologySLOs(target.getId());
            sloObserver.clearTopologySLOs(victim.getId());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void removeAlreadySelectedPairs(ArrayList<String> receivers, ArrayList<String> givers) {
        for (String target: targets.keySet()) {
            int targetIndex = receivers.indexOf(target);
            if (targetIndex != -1) {
                receivers.remove(targetIndex);
            }
        }

        for (String victim: victims.keySet()) {
            int victimIndex = givers.indexOf(victim);
            if (victimIndex != -1) {
                givers.remove(victimIndex);
            }
        }
    }

//    private void applyRebalancedScheduling(Cluster cluster, Topologies topologies) {
//        for (Map.Entry<String, String> targetToVictim: targetToVictimMapping.entrySet()) {
//            TopologyDetails target = topologies.getById(targetToVictim.getKey());
//            TopologyDetails victim = topologies.getById(targetToVictim.getValue());
//            ExecutorPair executorPair = targetToNodeMapping.get(targetToVictim.getKey());
//
//            reassignNewScheduling(target, victim, cluster, executorPair);
//        }
//    }

    private void reassignNewScheduling(TopologyDetails target, TopologyDetails victim, Cluster cluster,
                                       ExecutorPair executorPair) {

        ExecutorSummary targetExecutorSummary = executorPair.getTargetExecutorSummary();
        ExecutorSummary victimExecutorSummary = executorPair.getVictimExecutorSummary();

        WorkerSlot targetSlot = new WorkerSlot(targetExecutorSummary.get_host(), targetExecutorSummary.get_port());
        WorkerSlot victimSlot = new WorkerSlot(victimExecutorSummary.get_host(), victimExecutorSummary.get_port());

        Set<ExecutorDetails> previousTargetExecutors = globalState.getTopologySchedules().get(target.getId()).getExecutorToComponent().keySet();

        LOG.info("\n************** Target Topology **************");

        LOG.info("\n****** Previous Executors ******");
        for (ExecutorDetails executorDetails: previousTargetExecutors) {
            LOG.info(executorDetails.toString());
        }

        SchedulerAssignment currentTargetAssignment = cluster.getAssignmentById(target.getId());
        if (currentTargetAssignment != null) {
            Set<ExecutorDetails> currentTargetExecutors = currentTargetAssignment.getExecutorToSlot().keySet();
            currentTargetExecutors.removeAll(previousTargetExecutors);

            LOG.info("\n****** Current Executors ******");
            for (ExecutorDetails executorDetails: currentTargetExecutors) {
                LOG.info(executorDetails.toString());
            }

            LOG.info("\n********************** Found new executor *********************");
            for (ExecutorDetails newExecutor: currentTargetExecutors) {
                LOG.info(newExecutor.toString());
            }
        }

        LOG.info("\n************** Victim Topology **************");

        Set<ExecutorDetails> previousVictimExecutors = globalState.getTopologySchedules().get(victim.getId()).getExecutorToComponent().keySet();
        SchedulerAssignment currentVictimAssignment = cluster.getAssignmentById(victim.getId());

        LOG.info("\n****** Previous Executors ******");
        for (ExecutorDetails executorDetails: previousVictimExecutors) {
            LOG.info(executorDetails.toString());
        }

        if (currentVictimAssignment != null) {
            Set<ExecutorDetails> currentVictimExecutors = currentVictimAssignment.getExecutorToSlot().keySet();
            previousVictimExecutors.removeAll(currentVictimExecutors);

            LOG.info("\n****** Current Executors ******");
            for (ExecutorDetails executorDetails: currentVictimExecutors) {
                LOG.info(executorDetails.toString());
            }

            for (ExecutorDetails newExecutor: previousVictimExecutors) {
                LOG.info("********************** Removed executor *********************");
                LOG.info(newExecutor.toString());
            }
        }

//        Collection<ExecutorDetails> unassignedExecutors = cluster.getUnassignedExecutors(target);
//        cluster.freeSlot(targetSlot);
//        targetExecutorDetails.addAll(unassignedExecutors);
//        cluster.assign(targetSlot, target.getId(), targetExecutorDetails);

//
//        targetToVictimMapping.remove(target.getId());
//        targetToNodeMapping.remove(target.getId());
    }

    private List<ExecutorDetails> difference(Collection<ExecutorDetails> execs1,
                                             Collection<ExecutorDetails> execs2) {
        List<ExecutorDetails> result = new ArrayList<ExecutorDetails>();
        for (ExecutorDetails exec : execs1) {
            if(!execs2.contains(exec)) {
                result.add(exec);
            }
        }
        return result;

    }
}
