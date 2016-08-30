package io.joynr.discovery.jee;

/*
 * #%L
 * %%
 * Copyright (C) 2011 - 2016 BMW Car IT GmbH
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import java.util.List;

import javax.ejb.Stateless;
import javax.inject.Inject;
import javax.persistence.EntityManager;
import javax.transaction.Transactional;

import io.joynr.capabilities.GlobalDiscoveryEntryPersisted;
import io.joynr.jeeintegration.api.ServiceProvider;
import joynr.infrastructure.GlobalCapabilitiesDirectorySync;
import joynr.types.GlobalDiscoveryEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Stateless
@ServiceProvider(serviceInterface = GlobalCapabilitiesDirectorySync.class)
@Transactional
public class GlobalCapabilitiesDirectoryEjb implements GlobalCapabilitiesDirectorySync {

    private static final Logger logger = LoggerFactory.getLogger(GlobalCapabilitiesDirectoryEjb.class);

    private EntityManager entityManager;

    @Inject
    public GlobalCapabilitiesDirectoryEjb(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public void add(GlobalDiscoveryEntry[] globalDiscoveryEntries) {
        for (GlobalDiscoveryEntry entry : globalDiscoveryEntries) {
            if (entry != null) {
                add(entry);
            } else {
                logger.trace("Ignoring null entry passed in as part of array.");
            }
        }
    }

    @Override
    public void add(GlobalDiscoveryEntry globalDiscoveryEntry) {
        logger.debug("Adding global discovery entry {}", globalDiscoveryEntry);
        // TODO set clusterControllerId from globalDiscoveryEntry
        GlobalDiscoveryEntryPersisted entity = new GlobalDiscoveryEntryPersisted(globalDiscoveryEntry,
                                                                                 "clusterControllerId");
        entityManager.persist(entity);
    }

    @Override
    public GlobalDiscoveryEntry[] lookup(String[] domains, String interfaceName) {
        logger.debug("Looking up global discovery entries for domains {} and interface name {}", domains, interfaceName);
        String queryString = "from GlobalDiscoveryEntryPersisted where domain in :domains and interfaceName = :interfaceName";
        List<GlobalDiscoveryEntryPersisted> queryResult = entityManager.createQuery(queryString,
                                                                                    GlobalDiscoveryEntryPersisted.class)
                                                                       .setParameter("domains", domains)
                                                                       .setParameter("interfaceName", interfaceName)
                                                                       .getResultList();
        logger.debug("Found discovery entries: {}", queryResult);
        return queryResult.toArray(new GlobalDiscoveryEntry[queryResult.size()]);
    }

    @Override
    public GlobalDiscoveryEntry lookup(String participantId) {
        logger.debug("Looking up global discovery entry for participant ID {}", participantId);
        GlobalDiscoveryEntryPersisted result = entityManager.find(GlobalDiscoveryEntryPersisted.class, participantId);
        logger.debug("Found entry {}", result);
        return result;
    }

    @Override
    public void remove(String[] participantIds) {
        logger.debug("Removing global discovery entries with IDs {}", participantIds);
        String queryString = "delete from GlobalDiscoveryEntryPersisted where particpantId in :participantIds";
        int deletedCount = entityManager.createQuery(queryString, GlobalDiscoveryEntryPersisted.class)
                                        .setParameter("participantIds", participantIds)
                                        .executeUpdate();
        logger.debug("Deleted {} entries (number of IDs passed in {})", deletedCount, participantIds.length);
    }

    @Override
    public void remove(String participantId) {
        remove(new String[]{ participantId });
    }

    @Override
    public void touch(String clusterControllerId) {
        logger.debug("Touch called. Doesn't currently do anything.");
    }

}
