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

package org.apache.flink.runtime.client;

import akka.actor.ActorRef;
import akka.actor.PoisonPill;
import akka.actor.Status;
import akka.actor.Terminated;
import akka.actor.UntypedActor;
import org.apache.flink.runtime.jobgraph.JobGraph;
import org.apache.flink.runtime.messages.ExecutionGraphMessages;
import org.apache.flink.runtime.messages.JobClientMessages;
import org.apache.flink.runtime.messages.JobManagerMessages;
import org.slf4j.Logger;

/**
 * Actor which constitutes the bridge between the non-actor code and the JobManager. The JobClient
 * is used to submit jobs to the JobManager and to request the port of the BlobManager.
 */
public class JobClientActor extends UntypedActor {
	
	private final ActorRef jobManager;
	private final Logger logger;
	private final boolean sysoutUpdates;
	
	private ActorRef submitter;
	
	
	public JobClientActor(ActorRef jobManager, Logger logger, boolean sysoutUpdates) {
		if (jobManager == null || logger == null) {
			throw new NullPointerException();
		}
		this.jobManager = jobManager;
		this.logger = logger;
		this.sysoutUpdates = sysoutUpdates;
	}
	
	@Override
	public void onReceive(Object message) {
		
		// =========== State Change Messages ===============

		if (message instanceof ExecutionGraphMessages.ExecutionStateChanged) {
			logAndPrintMessage(message);
		}
		else if (message instanceof ExecutionGraphMessages.JobStatusChanged) {
			logAndPrintMessage(message);
		}

		// =========== Job Life Cycle Messages ===============
		
		// submit a job to the JobManager
		else if (message instanceof JobClientMessages.SubmitJobAndWait) {
			// sanity check that this no job was submitted through this actor before -
			// it is a one-shot actor after all
			if (this.submitter == null) {
				JobGraph jobGraph = ((JobClientMessages.SubmitJobAndWait) message).jobGraph();
				if (jobGraph == null) {
					logger.error("Received null JobGraph");
					sender().tell(new Status.Failure(new Exception("JobGraph is null")), getSelf());
				}
				else {
					logger.info("Sending message to JobManager {} to submit job {} ({}) and wait for progress",
							jobManager.path().toString(), jobGraph.getName(), jobGraph.getJobID());

					this.submitter = getSender();
					jobManager.tell(new JobManagerMessages.SubmitJob(jobGraph, true), getSelf());
					
					// make sure we notify the sender when the connection got lost
					getContext().watch(jobManager);
				}
			}
			else {
				// repeated submission - tell failure to sender and kill self
				String msg = "Received repeated 'SubmitJobAndWait'";
				logger.error(msg);
				getSender().tell(new Status.Failure(new Exception(msg)), ActorRef.noSender());

				getContext().unwatch(jobManager);
				getSelf().tell(PoisonPill.getInstance(), ActorRef.noSender());
			}
		}
		// acknowledgement to submit job is only logged, our original
		// submitter is only interested in the final job result
		else if (message instanceof JobManagerMessages.JobResultSuccess) {
			// forward the success to the original job submitter
			logger.debug("Received JobResultSuccess message from JobManager");
			if (this.submitter != null) {
				this.submitter.tell(message, getSelf());
			}
			
			// we are done, stop ourselves
			getContext().unwatch(jobManager);
			getSelf().tell(PoisonPill.getInstance(), ActorRef.noSender());
		}
		else if (message instanceof Status.Success) {
			// job was successfully submitted :-)
			logger.info("Job was successfully submitted to the JobManager");
		}
		else if (message instanceof Status.Failure) {
			// job execution failed, inform the actor that submitted the job
			logger.debug("Received failure from JobManager", ((Status.Failure) message).cause());
			if (submitter != null) {
				submitter.tell(message, sender());
			}
		}

		// =========== Actor / Communication Failure ===============
		
		else if (message instanceof Terminated) {
			ActorRef target = ((Terminated) message).getActor();
			if (jobManager.equals(target)) {
				String msg = "Lost connection to JobManager " + jobManager.path();
				logger.info(msg);
				submitter.tell(new Status.Failure(new Exception(msg)), getSelf());
			} else {
				logger.error("Received 'Terminated' for unknown actor " + target);
			}
		}

		// =========== Unknown Messages ===============
		
		else {
			logger.error("JobClient received unknown message: " + message);
		}
	}
	
	private void logAndPrintMessage(Object message) {
		logger.info(message.toString());
		if (sysoutUpdates) {
			System.out.println(message.toString());
		}
	}
}
