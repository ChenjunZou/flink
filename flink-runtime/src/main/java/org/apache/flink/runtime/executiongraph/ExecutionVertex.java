/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.runtime.executiongraph;

import org.apache.flink.runtime.deployment.PartialPartitionInfo;
import org.apache.flink.runtime.instance.InstanceConnectionInfo;
import org.apache.flink.runtime.instance.SimpleSlot;
import org.apache.flink.runtime.JobException;
import org.apache.flink.runtime.blob.BlobKey;
import org.apache.flink.runtime.deployment.PartitionConsumerDeploymentDescriptor;
import org.apache.flink.runtime.deployment.PartitionDeploymentDescriptor;
import org.apache.flink.runtime.deployment.PartitionInfo;
import org.apache.flink.runtime.deployment.TaskDeploymentDescriptor;
import org.apache.flink.runtime.execution.ExecutionState;
import org.apache.flink.runtime.instance.Instance;
import org.apache.flink.runtime.jobgraph.DistributionPattern;
import org.apache.flink.runtime.jobgraph.IntermediateDataSetID;
import org.apache.flink.runtime.jobgraph.JobEdge;
import org.apache.flink.runtime.jobgraph.JobID;
import org.apache.flink.runtime.jobgraph.JobVertexID;
import org.apache.flink.runtime.jobmanager.scheduler.CoLocationConstraint;
import org.apache.flink.runtime.jobmanager.scheduler.CoLocationGroup;
import org.apache.flink.runtime.jobmanager.scheduler.NoResourceAvailableException;
import org.apache.flink.runtime.jobmanager.scheduler.Scheduler;
import org.slf4j.Logger;

import scala.concurrent.duration.FiniteDuration;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.google.common.base.Preconditions.checkElementIndex;
import static org.apache.flink.runtime.execution.ExecutionState.CANCELED;
import static org.apache.flink.runtime.execution.ExecutionState.FAILED;
import static org.apache.flink.runtime.execution.ExecutionState.FINISHED;

/**
 * The ExecutionVertex is a parallel subtask of the execution. It may be executed once, or several times, each of
 * which time it spawns an {@link Execution}.
 */
public class ExecutionVertex implements Serializable {

	private static final long serialVersionUID = 42L;

	private static final Logger LOG = ExecutionGraph.LOG;
	
	private static final int MAX_DISTINCT_LOCATIONS_TO_CONSIDER = 8;
	
	// --------------------------------------------------------------------------------------------
	
	private final ExecutionJobVertex jobVertex;
	
	private IntermediateResultPartition[] resultPartitions;
	
	private ExecutionEdge[][] inputEdges;

	private ConcurrentLinkedQueue<PartialPartitionInfo> partialPartitionInfos;
	
	private final int subTaskIndex;
	
	private final List<Execution> priorExecutions;

	private final FiniteDuration timeout;
	
	private volatile CoLocationConstraint locationConstraint;
	
	private volatile Execution currentExecution;	// this field must never be null
	
	
	private volatile List<Instance> locationConstraintInstances;
	
	private volatile boolean scheduleLocalOnly;
	
	// --------------------------------------------------------------------------------------------

	public ExecutionVertex(ExecutionJobVertex jobVertex, int subTaskIndex,
						IntermediateResult[] producedDataSets, FiniteDuration timeout) {
		this(jobVertex, subTaskIndex, producedDataSets, timeout, System.currentTimeMillis());
	}

	public ExecutionVertex(ExecutionJobVertex jobVertex, int subTaskIndex,
						IntermediateResult[] producedDataSets, FiniteDuration timeout,
						long createTimestamp) {
		this.jobVertex = jobVertex;
		this.subTaskIndex = subTaskIndex;

		this.resultPartitions = new IntermediateResultPartition[producedDataSets.length];
		for (int i = 0; i < producedDataSets.length; i++) {
			IntermediateResultPartition irp = new IntermediateResultPartition(producedDataSets[i], this, subTaskIndex);
			this.resultPartitions[i] = irp;
			producedDataSets[i].setPartition(subTaskIndex, irp);
		}

		this.inputEdges = new ExecutionEdge[jobVertex.getJobVertex().getInputs().size()][];

		this.partialPartitionInfos = new ConcurrentLinkedQueue<PartialPartitionInfo>();

		this.priorExecutions = new CopyOnWriteArrayList<Execution>();

		this.currentExecution = new Execution(this, 0, createTimestamp, timeout);

		// create a co-location scheduling hint, if necessary
		CoLocationGroup clg = jobVertex.getCoLocationGroup();
		if (clg != null) {
			this.locationConstraint = clg.getLocationConstraint(subTaskIndex);
		}
		else {
			this.locationConstraint = null;
		}

		this.timeout = timeout;
	}
	
	
	// --------------------------------------------------------------------------------------------
	//  Properties
	// --------------------------------------------------------------------------------------------
	
	public JobID getJobId() {
		return this.jobVertex.getJobId();
	}
	
	public ExecutionJobVertex getJobVertex() {
		return jobVertex;
	}
	
	public JobVertexID getJobvertexId() {
		return this.jobVertex.getJobVertexId();
	}
	
	public String getTaskName() {
		return this.jobVertex.getJobVertex().getName();
	}
	
	public int getTotalNumberOfParallelSubtasks() {
		return this.jobVertex.getParallelism();
	}
	
	public int getParallelSubtaskIndex() {
		return this.subTaskIndex;
	}
	
	public int getNumberOfInputs() {
		return this.inputEdges.length;
	}
	
	public ExecutionEdge[] getInputEdges(int input) {
		if (input < 0 || input >= this.inputEdges.length) {
			throw new IllegalArgumentException(String.format("Input %d is out of range [0..%d)", input, this.inputEdges.length));
		}
		return inputEdges[input];
	}
	
	public CoLocationConstraint getLocationConstraint() {
		return locationConstraint;
	}
	
	public Execution getCurrentExecutionAttempt() {
		return currentExecution;
	}
	
	public ExecutionState getExecutionState() {
		return currentExecution.getState();
	}
	
	public long getStateTimestamp(ExecutionState state) {
		return currentExecution.getStateTimestamp(state);
	}
	
	public Throwable getFailureCause() {
		return currentExecution.getFailureCause();
	}
	
	public SimpleSlot getCurrentAssignedResource() {
		return currentExecution.getAssignedResource();
	}
	
	public InstanceConnectionInfo getCurrentAssignedResourceLocation() {
		return currentExecution.getAssignedResourceLocation();
	}
	
	public ExecutionGraph getExecutionGraph() {
		return this.jobVertex.getGraph();
	}

	public ConcurrentLinkedQueue<PartialPartitionInfo> getPartialPartitionInfos() {
		return partialPartitionInfos;
	}
	
	// --------------------------------------------------------------------------------------------
	//  Graph building
	// --------------------------------------------------------------------------------------------
	
	public void connectSource(int inputNumber, IntermediateResult source, JobEdge edge, int consumerNumber) {
		
		final DistributionPattern pattern = edge.getDistributionPattern();
		final IntermediateResultPartition[] sourcePartitions = source.getPartitions();
		
		ExecutionEdge[] edges;
		
		switch (pattern) {
			case POINTWISE:
				edges = connectPointwise(sourcePartitions, inputNumber);
				break;

			case ALL_TO_ALL:
				edges = connectAllToAll(sourcePartitions, inputNumber);
				break;
				
			default:
				throw new RuntimeException("Unrecognized distribution pattern.");
		
		}
		
		this.inputEdges[inputNumber] = edges;
		
		// add the consumers to the source
		// for now (until the receiver initiated handshake is in place), we need to register the 
		// edges as the execution graph
		for (ExecutionEdge ee : edges) {
			ee.getSource().addConsumer(ee, consumerNumber);
		}
	}
	
	private ExecutionEdge[] connectAllToAll(IntermediateResultPartition[] sourcePartitions, int inputNumber) {
		ExecutionEdge[] edges = new ExecutionEdge[sourcePartitions.length];
		
		for (int i = 0; i < sourcePartitions.length; i++) {
			IntermediateResultPartition irp = sourcePartitions[i];
			edges[i] = new ExecutionEdge(irp, this, inputNumber);
		}
		
		return edges;
	}
	
	private ExecutionEdge[] connectPointwise(IntermediateResultPartition[] sourcePartitions, int inputNumber) {
		final int numSources = sourcePartitions.length;
		final int parallelism = getTotalNumberOfParallelSubtasks();
	
		// simple case same number of sources as targets
		if (numSources == parallelism) {
			return new ExecutionEdge[] { new ExecutionEdge(sourcePartitions[subTaskIndex], this, inputNumber) };
		}
		else if (numSources < parallelism) {

			int sourcePartition;

			// check if the pattern is regular or irregular
			// we use int arithmetics for regular, and floating point with rounding for irregular
			if (parallelism % numSources == 0) {
				// same number of targets per source
				int factor = parallelism / numSources;
				sourcePartition = subTaskIndex / factor;
			}
			else {
				// different number of targets per source
				float factor = ((float) parallelism) / numSources;
				sourcePartition = (int) (subTaskIndex / factor);
			}

			return new ExecutionEdge[] { new ExecutionEdge(sourcePartitions[sourcePartition], this, inputNumber) };
		}
		else {
			if (numSources % parallelism == 0) {
				// same number of targets per source
				int factor = numSources / parallelism;
				int startIndex = subTaskIndex * factor;

				ExecutionEdge[] edges = new ExecutionEdge[factor];
				for (int i = 0; i < factor; i++) {
					edges[i] = new ExecutionEdge(sourcePartitions[startIndex + i], this, inputNumber);
				}
				return edges;
			}
			else {
				float factor = ((float) numSources) / parallelism;

				int start = (int) (subTaskIndex * factor);
				int end = (subTaskIndex == getTotalNumberOfParallelSubtasks() - 1) ?
						sourcePartitions.length : 
						(int) ((subTaskIndex + 1) * factor);

				ExecutionEdge[] edges = new ExecutionEdge[end - start];
				for (int i = 0; i < edges.length; i++) {
					edges[i] = new ExecutionEdge(sourcePartitions[start + i], this, inputNumber);
				}

				return edges;
			}
		}
	}
	
	public void setLocationConstraintHosts(List<Instance> instances) {
		this.locationConstraintInstances = instances;
	}
	
	public void setScheduleLocalOnly(boolean scheduleLocalOnly) {
		if (scheduleLocalOnly && inputEdges != null && inputEdges.length > 0) {
			throw new IllegalArgumentException("Strictly local scheduling is only supported for sources.");
		}
		
		this.scheduleLocalOnly = scheduleLocalOnly;
	}

	public boolean isScheduleLocalOnly() {
		return scheduleLocalOnly;
	}
	
	/**
	 * Gets the location preferences of this task, determined by the locations of the predecessors from which
	 * it receives input data.
	 * If there are more than MAX_DISTINCT_LOCATIONS_TO_CONSIDER different locations of source data, this
	 * method returns {@code null} to indicate no location preference.
	 * 
	 * @return The preferred locations for this vertex execution, or null, if there is no preference.
	 */
	public Iterable<Instance> getPreferredLocations() {
		// if we have hard location constraints, use those
		{
			List<Instance> constraintInstances = this.locationConstraintInstances;
			if (constraintInstances != null && !constraintInstances.isEmpty()) {
				return constraintInstances;
			}
		}
		
		// otherwise, base the preferred locations on the input connections
		if (inputEdges == null) {
			return Collections.emptySet();
		}
		else {
			HashSet<Instance> locations = new HashSet<Instance>();
		
			for (int i = 0; i < inputEdges.length; i++) {
				ExecutionEdge[] sources = inputEdges[i];
				if (sources != null) {
					for (int k = 0; k < sources.length; k++) {
						SimpleSlot sourceSlot = sources[k].getSource().getProducer().getCurrentAssignedResource();
						if (sourceSlot != null) {
							locations.add(sourceSlot.getInstance());
							if (locations.size() > MAX_DISTINCT_LOCATIONS_TO_CONSIDER) {
								return null;
							}
						}
					}
				}
			}
			return locations;
		}
	}

	// --------------------------------------------------------------------------------------------
	//   Actions
	// --------------------------------------------------------------------------------------------

	public void resetForNewExecution() {
		
		if (LOG.isDebugEnabled()) {
			LOG.debug("Resetting exection vertex {} for new execution.", getSimpleName());
		}
		
		synchronized (priorExecutions) {
			Execution execution = currentExecution;
			ExecutionState state = execution.getState();

			if (state == FINISHED || state == CANCELED || state == FAILED) {
				priorExecutions.add(execution);
				currentExecution = new Execution(this, execution.getAttemptNumber()+1,
						System.currentTimeMillis(), timeout);
				
				CoLocationGroup grp = jobVertex.getCoLocationGroup();
				if (grp != null) {
					this.locationConstraint = grp.getLocationConstraint(subTaskIndex);
				}
			}
			else {
				throw new IllegalStateException("Cannot reset a vertex that is in state " + state);
			}
		}
	}

	public boolean scheduleForExecution(Scheduler scheduler, boolean queued) throws NoResourceAvailableException {
		return this.currentExecution.scheduleForExecution(scheduler, queued);
	}

	public void deployToSlot(SimpleSlot slot) throws JobException {
		this.currentExecution.deployToSlot(slot);
	}

	public void cancel() {
		this.currentExecution.cancel();
	}

	public void fail(Throwable t) {
		this.currentExecution.fail(t);
	}

	/**
	 * Schedules or updates the {@link IntermediateResultPartition} consumer
	 * tasks of the intermediate result partition with the given index.
	 */
	void scheduleOrUpdateConsumers(int partitionIndex) {
		checkElementIndex(partitionIndex, resultPartitions.length);

		IntermediateResultPartition partition = resultPartitions[partitionIndex];

		currentExecution.scheduleOrUpdateConsumers(partition.getConsumers());
	}
	
	/**
	 * This method cleans fields that are irrelevant for the archived execution attempt.
	 */
	public void prepareForArchiving() throws IllegalStateException {
		Execution execution = currentExecution;
		ExecutionState state = execution.getState();

		// sanity check
		if (!(state == FINISHED || state == CANCELED || state == FAILED)) {
			throw new IllegalStateException("Cannot archive ExecutionVertex that is not in a finished state.");
		}
		
		// prepare the current execution for archiving
		execution.prepareForArchiving();
		
		// prepare previous executions for archiving
		for (Execution exec : priorExecutions) {
			exec.prepareForArchiving();
		}
		
		// clear the unnecessary fields in this class
		this.resultPartitions = null;
		this.inputEdges = null;
		this.locationConstraintInstances = null;
		this.partialPartitionInfos.clear();
		this.partialPartitionInfos = null;
	}

	public void cachePartitionInfo(PartialPartitionInfo partitionInfo){
		this.partialPartitionInfos.add(partitionInfo);
	}

	void sendPartitionInfos() {
		currentExecution.sendPartitionInfos();
	}

	// --------------------------------------------------------------------------------------------
	//   Notifications from the Execution Attempt
	// --------------------------------------------------------------------------------------------

	void executionFinished() {
		jobVertex.vertexFinished(subTaskIndex);
	}

	void executionCanceled() {
		jobVertex.vertexCancelled(subTaskIndex);
	}

	void executionFailed(Throwable t) {
		jobVertex.vertexFailed(subTaskIndex, t);
	}

	// --------------------------------------------------------------------------------------------
	//   Miscellaneous
	// --------------------------------------------------------------------------------------------

	/**
	 * Simply forward this notification. This is for logs and event archivers.
	 */
	void notifyStateTransition(ExecutionAttemptID executionId, ExecutionState newState, Throwable error) {
		getExecutionGraph().notifyExecutionChange(getJobvertexId(), subTaskIndex, executionId, newState, error);
	}
	
	TaskDeploymentDescriptor createDeploymentDescriptor(ExecutionAttemptID executionId, SimpleSlot slot) {
		// Produced intermediate results
		List<PartitionDeploymentDescriptor> producedPartitions = new ArrayList<PartitionDeploymentDescriptor>(resultPartitions.length);

		for (IntermediateResultPartition partition : resultPartitions) {
			producedPartitions.add(PartitionDeploymentDescriptor.fromIntermediateResultPartition(partition));
		}

		// Consumed intermediate results
		List<PartitionConsumerDeploymentDescriptor> consumedPartitions = new ArrayList<PartitionConsumerDeploymentDescriptor>();

		for (ExecutionEdge[] edges : inputEdges) {
			PartitionInfo[] partitions = PartitionInfo.fromEdges(edges, slot);

			// If the produced partition has multiple consumers registered, we
			// need to request the one matching our sub task index.
			// TODO Refactor after removing the consumers from the intermediate result partitions
			int numConsumerEdges = edges[0].getSource().getConsumers().get(0).size();

			int queueToRequest = subTaskIndex % numConsumerEdges;

			IntermediateDataSetID resultId = edges[0].getSource().getIntermediateResult().getId();

			consumedPartitions.add(new PartitionConsumerDeploymentDescriptor(resultId, partitions, queueToRequest));
		}

		List<BlobKey> jarFiles = getExecutionGraph().getRequiredJarFiles();

		return new TaskDeploymentDescriptor(getJobId(), getJobvertexId(), executionId, getTaskName(),
				subTaskIndex, getTotalNumberOfParallelSubtasks(), getExecutionGraph().getJobConfiguration(),
				jobVertex.getJobVertex().getConfiguration(), jobVertex.getJobVertex().getInvokableClassName(),
				producedPartitions, consumedPartitions, jarFiles, slot.getSlotNumber());
	}

	// --------------------------------------------------------------------------------------------
	//  Utilities
	// --------------------------------------------------------------------------------------------

	/**
	 * Creates a simple name representation in the style 'taskname (x/y)', where
	 * 'taskname' is the name as returned by {@link #getTaskName()}, 'x' is the parallel
	 * subtask index as returned by {@link #getParallelSubtaskIndex()}{@code + 1}, and 'y' is the total
	 * number of tasks, as returned by {@link #getTotalNumberOfParallelSubtasks()}.
	 *
	 * @return A simple name representation.
	 */
	public String getSimpleName() {
		return getTaskName() + " (" + (getParallelSubtaskIndex()+1) + '/' + getTotalNumberOfParallelSubtasks() + ')';
	}

	@Override
	public String toString() {
		return getSimpleName();
	}
}
