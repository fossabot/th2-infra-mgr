/*
 * Copyright 2020-2020 Exactpro (Exactpro Systems Limited)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.exactpro.th2.schema.schemamanager;

import com.exactpro.th2.schema.schemamanager.k8s.K8sCustomResource;
import com.exactpro.th2.schema.schemamanager.k8s.Kubernetes;
import com.exactpro.th2.schema.schemamanager.models.RepositorySnapshot;
import com.exactpro.th2.schema.schemamanager.models.ResourceEntry;
import com.exactpro.th2.schema.schemamanager.models.ResourceType;
import com.exactpro.th2.schema.schemamanager.models.Th2CustomResource;
import com.exactpro.th2.schema.schemamanager.repository.Gitter;
import com.exactpro.th2.schema.schemamanager.repository.Repository;
import com.exactpro.th2.schema.schemamanager.repository.RepositoryUpdateEvent;
import com.exactpro.th2.schema.schemamanager.util.Stringifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import rx.schedulers.Schedulers;

import javax.annotation.PostConstruct;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Component
public class K8sSynchronization {

    private static final int SYNC_PARALELIZATION_THREADS = 3;
    private static final Logger logger = LoggerFactory.getLogger(K8sSynchronization.class);
    private Config config;
    private K8sSynchronizationJobQueue jobQueue = new K8sSynchronizationJobQueue();

    private void synchronizeNamespace(String schemaName, Map<ResourceType, Map<String, ResourceEntry>> repositoryEntries) throws Exception {

        try (Kubernetes kube = new Kubernetes(config.getKubernetes(), schemaName);) {

            kube.ensureNameSpace();

            // load custom resources from k8s
            Map<ResourceType, Map<String, K8sCustomResource>> k8sEntries = new HashMap<>();
            for (ResourceType t : ResourceType.values())
                if (t.isK8sResource())
                    k8sEntries.put(t, kube.loadCustomResources(t, Th2CustomResource.GROUP, Th2CustomResource.VERSION));

            // synchronize by resource type
            for (ResourceType resourceType : ResourceType.values())
                if (resourceType.isK8sResource()) {
                    Map<String, ResourceEntry> entries = repositoryEntries.get(resourceType);
                    Map<String, K8sCustomResource> customResources = k8sEntries.get(resourceType);

                    for (ResourceEntry entry: entries.values()) {
                        String resourceName = entry.getName();

                        // check repository items against k8s
                        if (!customResources.containsKey(resourceName)) {
                            // create custom resources that do not exist in k8s
                            logger.info("Creating Custom Resource ({}) \"{}.{}\"", resourceType.kind(), schemaName, resourceName);
                            Th2CustomResource resource = new Th2CustomResource(entry);
                            try {
                                Stringifier.stringify(resource.getSpec());
                                kube.createCustomResource(resource);
                            } catch (Exception e) {
                                logger.error("Exception creating Custom Resource ({}) \"{}.{}\" ({})", resourceType.kind(), schemaName, resourceName, e.getMessage());
                            }
                        } else {
                            // compare object's hashes and update custom resources who's hash labels do not match
                            K8sCustomResource cr = customResources.get(resourceName);

                            if (!(entry.getSourceHash() == null || entry.getSourceHash().equals(cr.getSourceHashLabel()))) {
                                // update custopm resource
                                logger.info("Updating Custom Resource ({}) \"{}.{}\"", resourceType.kind(), schemaName, resourceName);
                                Th2CustomResource resource = new Th2CustomResource(entry);
                                try {
                                    Stringifier.stringify(resource.getSpec());
                                    kube.replaceCustomResource(resource);
                                } catch (Exception e) {
                                    logger.error("Exception updating Custom Resource ({}) \"{}.{}\" ({})", resourceType.kind(), schemaName, resourceName, e);
                                }
                            }
                        }
                    }

                    // delete k8s resources that do not exist in repository
                    for (String resourceName : customResources.keySet())
                        if (!entries.containsKey(resourceName))
                        try {
                            logger.info("Deleting Custom Resource ({}) \"{}.{}\"", resourceType.kind(), schemaName, resourceName);
                            ResourceEntry entry = new ResourceEntry();
                            entry.setKind(resourceType);
                            entry.setName(resourceName);
                            kube.deleteCustomResource(new Th2CustomResource(entry));
                        } catch (Exception e) {
                            logger.error("Exception deleting Custom Resource ({}) \"{}.{}\" ({})", resourceType.kind(), schemaName, resourceName, e);
                        }
            }
        } catch (Exception e) {
            throw e;
        }
    }


    private void synchronizeBranch(String branch) {

        try {
            logger.info("Checking schema settings \"{}\"", branch);

            // get repository items
            Gitter gitter = Gitter.getBranch(config.getGit(), branch);
            RepositorySnapshot snapshot = Repository.getSnapshot(gitter);
            Set<ResourceEntry> repositoryEntries = snapshot.getResources();

            if (snapshot.getRepositorySettings() == null || !snapshot.getRepositorySettings().isK8sPropagationEnabled()) {
                logger.info("Ignoring schema \"{}\" as it is not configured for synchronization", branch);
                return;
            }

            logger.info("Proceeding with schema \"{}\"", branch);

            // convert to map
            Map<ResourceType, Map<String, ResourceEntry>> repositoryMap = new HashMap<>();
            for (ResourceType t : ResourceType.values())
                if (t.isK8sResource())
                    repositoryMap.put(t, new HashMap<>());

            for (ResourceEntry entry : repositoryEntries)
                if (entry.getKind().isK8sResource()) {
                    Map<String, ResourceEntry> typeMap = repositoryMap.get(entry.getKind());
                    repositoryMap.putIfAbsent(entry.getKind(), typeMap);
                    typeMap.put(entry.getName(), entry);
                }

            // synchronize entries
            synchronizeNamespace(branch, repositoryMap);

        } catch (Exception e) {
            logger.error("Exception synchronizing schema \"{}\": {}", branch, e);
        }
    }


    @PostConstruct
    public void start() {
        logger.info("Starting Kubernetes synchronization phase");

        try {
            config = Config.getInstance();
            Map<String, String> branches = Gitter.getAllBranchesCommits(config.getGit());

            ExecutorService executor = Executors.newFixedThreadPool(SYNC_PARALELIZATION_THREADS);
            for (String branch : branches.keySet())
                if (!branch.equals("master"))
                    executor.execute(() -> synchronizeBranch(branch));

            executor.shutdown();
            try {
                executor.awaitTermination(Long.MAX_VALUE, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
            }

        } catch (Exception e) {
            logger.error("Exception fetching branch list from repository", e);
            //throw new RuntimeException("Kubernetes synchronization failed");
        }

        logger.info("Kubernetes synchronization phase complete");


        // start repository event listener threads
        ExecutorService executor = Executors.newCachedThreadPool();
        for (int i = 0; i < SYNC_PARALELIZATION_THREADS; i++)
            executor.execute(() -> processRepositoryEvents());

        SchemaEventRouter router = SchemaEventRouter.getInstance();
        router.getObservable()
                .observeOn(Schedulers.computation())
                .filter(event ->
                        (event instanceof RepositoryUpdateEvent && !((RepositoryUpdateEvent) event).isSyncingK8s()))
                .subscribe(event -> {
                    jobQueue.addJob(new K8sSynchronizationJobQueue.Job(event.getSchema()));
                });

        logger.info("Kubernetes synchronization process subscribed to git events");
    }

    private void processRepositoryEvents() {

        logger.info("Kubernetes synchronization thread started. waiting for synchronization events");
        while (!Thread.currentThread().isInterrupted()) {
            try {
                K8sSynchronizationJobQueue.Job job = jobQueue.takeJob();
                if (job == null) {
                    Thread.sleep(1000);
                    continue;
                }

                synchronizeBranch(job.getSchema());
                jobQueue.completeJob(job);

            } catch (InterruptedException e) {
                break;
            }
        }
        logger.info("Leaving Kubernetes synchronization thread: interrupt signal received");
    }

}