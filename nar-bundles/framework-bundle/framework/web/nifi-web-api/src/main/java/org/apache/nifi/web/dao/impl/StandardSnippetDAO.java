/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.nifi.web.dao.impl;

import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.controller.FlowController;
import org.apache.nifi.controller.ProcessorNode;
import org.apache.nifi.controller.Snippet;
import org.apache.nifi.controller.StandardSnippet;
import org.apache.nifi.controller.exception.ProcessorInstantiationException;
import org.apache.nifi.groups.ProcessGroup;
import org.apache.nifi.web.NiFiCoreException;
import org.apache.nifi.web.ResourceNotFoundException;
import org.apache.nifi.web.api.dto.FlowSnippetDTO;
import org.apache.nifi.web.api.dto.ProcessGroupDTO;
import org.apache.nifi.web.api.dto.ProcessorConfigDTO;
import org.apache.nifi.web.api.dto.ProcessorDTO;
import org.apache.nifi.web.api.dto.SnippetDTO;
import org.apache.nifi.web.dao.SnippetDAO;
import org.apache.nifi.web.util.SnippetUtils;

import org.apache.commons.lang3.StringUtils;

/**
 *
 */
public class StandardSnippetDAO implements SnippetDAO {

    private FlowController flowController;
    private SnippetUtils snippetUtils;

    private StandardSnippet locateSnippet(String snippetId) {
        final StandardSnippet snippet = flowController.getSnippetManager().getSnippet(snippetId);

        if (snippet == null) {
            throw new ResourceNotFoundException(String.format("Unable to find snippet with id '%s'.", snippetId));
        }

        return snippet;
    }

    /**
     * Creates a new snippet based off of an existing snippet. Used for copying
     * and pasting.
     *
     * @param groupId
     * @param originX
     * @param originY
     * @return
     */
    @Override
    public FlowSnippetDTO copySnippet(final String groupId, final String snippetId, final Double originX, final Double originY) {
        try {
            // ensure the parent group exist
            final ProcessGroup processGroup = flowController.getGroup(groupId);
            if (processGroup == null) {
                throw new IllegalArgumentException("The specified parent process group could not be found");
            }

            // get the existing snippet
            Snippet existingSnippet = getSnippet(snippetId);

            // get the process group
            ProcessGroup existingSnippetProcessGroup = flowController.getGroup(existingSnippet.getParentGroupId());

            // ensure the group could be found
            if (existingSnippetProcessGroup == null) {
                throw new IllegalStateException("The parent process group for the existing snippet could not be found.");
            }

            // generate the snippet contents
            FlowSnippetDTO snippetContents = snippetUtils.populateFlowSnippet(existingSnippet, true);

            // resolve sensitive properties
            lookupSensitiveProperties(snippetContents);

            // copy snippet
            snippetContents = snippetUtils.copy(snippetContents, processGroup);

            // move the snippet if necessary
            if (originX != null && originY != null) {
                org.apache.nifi.util.SnippetUtils.moveSnippet(snippetContents, originX, originY);
            }

            // instantiate the snippet
            flowController.instantiateSnippet(processGroup, snippetContents);

            return snippetContents;
        } catch (ProcessorInstantiationException pie) {
            throw new NiFiCoreException(String.format("Unable to copy snippet because processor type '%s' is unknown to this NiFi.",
                    StringUtils.substringAfterLast(pie.getMessage(), ".")));
        }
    }

    /**
     * Creates a new snippet containing the specified components. Whether or not
     * the snippet is linked will determine whether actions on this snippet
     * actually affect the data flow.
     *
     * @return
     */
    @Override
    public Snippet createSnippet(final SnippetDTO snippetDTO) {
        // create the snippet request
        final StandardSnippet snippet = new StandardSnippet();
        snippet.setId(snippetDTO.getId());
        snippet.setParentGroupId(snippetDTO.getParentGroupId());
        snippet.setLinked(snippetDTO.isLinked());
        snippet.addProcessors(snippetDTO.getProcessors());
        snippet.addProcessGroups(snippetDTO.getProcessGroups());
        snippet.addRemoteProcessGroups(snippetDTO.getRemoteProcessGroups());
        snippet.addInputPorts(snippetDTO.getInputPorts());
        snippet.addOutputPorts(snippetDTO.getOutputPorts());
        snippet.addConnections(snippetDTO.getConnections());
        snippet.addLabels(snippetDTO.getLabels());
        snippet.addFunnels(snippetDTO.getFunnels());

        // ensure this snippet isn't empty
        if (snippet.isEmpty()) {
            throw new IllegalArgumentException("Cannot create an empty snippet.");
        }

        // ensure the parent group exist
        final ProcessGroup processGroup = flowController.getGroup(snippet.getParentGroupId());
        if (processGroup == null) {
            throw new IllegalArgumentException("The specified parent process group could not be found.");
        }

        // store the snippet
        flowController.getSnippetManager().addSnippet(snippet);
        return snippet;
    }

    @Override
    public void verifyDelete(String snippetId) {
        final StandardSnippet snippet = locateSnippet(snippetId);

        // only need to check if the snippet is linked
        if (snippet.isLinked()) {
            // ensure the parent group exist
            final ProcessGroup processGroup = flowController.getGroup(snippet.getParentGroupId());
            if (processGroup == null) {
                throw new IllegalArgumentException("The specified parent process group could not be found.");
            }

            // verify the processGroup can remove the snippet
            processGroup.verifyCanDelete(snippet);
        }
    }

    /**
     * Deletes a snippet. If the snippet is linked, also deletes the underlying
     * components.
     *
     * @param snippetId
     */
    @Override
    public void deleteSnippet(String snippetId) {
        final StandardSnippet snippet = locateSnippet(snippetId);

        // if the snippet is linked, remove the contents
        if (snippet.isLinked()) {
            final ProcessGroup processGroup = flowController.getGroup(snippet.getParentGroupId());
            if (processGroup == null) {
                throw new IllegalArgumentException("The specified parent process group could not be found.");
            }

            // remove the underlying components
            processGroup.remove(snippet);
        }

        // delete the snippet itself
        flowController.getSnippetManager().removeSnippet(snippet);
    }

    @Override
    public Snippet getSnippet(String snippetId) {
        return locateSnippet(snippetId);
    }

    @Override
    public boolean hasSnippet(String snippetId) {
        return flowController.getSnippetManager().getSnippet(snippetId) != null;
    }

    @Override
    public void verifyUpdate(SnippetDTO snippetDTO) {
        final StandardSnippet snippet = locateSnippet(snippetDTO.getId());

        // if attempting to move the snippet contents
        if (snippetDTO.getParentGroupId() != null) {
            // get the current process group
            final ProcessGroup processGroup = flowController.getGroup(snippet.getParentGroupId());
            if (processGroup == null) {
                throw new IllegalArgumentException("The specified parent process group could not be found.");
            }

            // get the new process group
            final ProcessGroup newProcessGroup = flowController.getGroup(snippetDTO.getParentGroupId());
            if (newProcessGroup == null) {
                throw new IllegalArgumentException("The new process group could not be found.");
            }

            boolean verificationRequired = false;

            // verify if necessary
            if (snippetDTO.isLinked() != null) {
                if (snippetDTO.isLinked()) {
                    verificationRequired = true;
                }
            } else if (snippet.isLinked()) {
                verificationRequired = true;
            }

            // perform the verification if necessary
            if (verificationRequired) {
                processGroup.verifyCanMove(snippet, newProcessGroup);
            }
        }
    }

    /**
     * Updates the specified snippet. If the snippet is linked, the underlying
     * components will be moved into the specified groupId.
     *
     * @return
     */
    @Override
    public Snippet updateSnippet(final SnippetDTO snippetDTO) {
        final StandardSnippet snippet = locateSnippet(snippetDTO.getId());

        // update whether this snippet is linked to the data flow
        if (snippetDTO.isLinked() != null) {
            snippet.setLinked(snippetDTO.isLinked());
        }

        // if the group is changing and its linked to the data flow move it
        if (snippetDTO.getParentGroupId() != null && snippet.isLinked()) {
            final ProcessGroup currentProcessGroup = flowController.getGroup(snippet.getParentGroupId());
            if (currentProcessGroup == null) {
                throw new IllegalArgumentException("The current process group could not be found.");
            }

            final ProcessGroup newProcessGroup = flowController.getGroup(snippetDTO.getParentGroupId());
            if (newProcessGroup == null) {
                throw new IllegalArgumentException("The new process group could not be found.");
            }

            // move the snippet
            currentProcessGroup.move(snippet, newProcessGroup);

            // update its parent group id
            snippet.setParentGroupId(snippetDTO.getParentGroupId());
        }

        return snippet;
    }

    /**
     * Looks up the actual value for any sensitive properties from the specified
     * snippet.
     *
     * @param snippet
     */
    private void lookupSensitiveProperties(final FlowSnippetDTO snippet) {
        // ensure that contents have been specified
        if (snippet != null) {
            // go through each processor if specified
            if (snippet.getProcessors() != null) {
                lookupSensitiveProperties(snippet.getProcessors());
            }

            // go through each process group if specified
            if (snippet.getProcessGroups() != null) {
                for (final ProcessGroupDTO group : snippet.getProcessGroups()) {
                    lookupSensitiveProperties(group.getContents());
                }
            }
        }
    }

    /**
     * Looks up the actual value for any sensitive properties from the specified
     * processors.
     *
     * @param snippet
     */
    private void lookupSensitiveProperties(final Set<ProcessorDTO> processors) {
        final ProcessGroup rootGroup = flowController.getGroup(flowController.getRootGroupId());

        // go through each processor
        for (final ProcessorDTO processorDTO : processors) {
            final ProcessorConfigDTO processorConfig = processorDTO.getConfig();

            // ensure that some property configuration have been specified
            if (processorConfig != null && processorConfig.getProperties() != null) {
                final Map<String, String> processorProperties = processorConfig.getProperties();

                // find the corresponding processor
                final ProcessorNode processorNode = rootGroup.findProcessor(processorDTO.getId());
                if (processorNode == null) {
                    throw new IllegalArgumentException(String.format("Unable to create snippet because Processor '%s' could not be found", processorDTO.getId()));
                }

                // look for sensitive properties get the actual value
                for (Entry<PropertyDescriptor, String> entry : processorNode.getProperties().entrySet()) {
                    final PropertyDescriptor descriptor = entry.getKey();

                    if (descriptor.isSensitive()) {
                        processorProperties.put(descriptor.getName(), entry.getValue());
                    }
                }
            }
        }
    }

    /* setters */
    public void setFlowController(FlowController flowController) {
        this.flowController = flowController;
    }

    public void setSnippetUtils(SnippetUtils snippetUtils) {
        this.snippetUtils = snippetUtils;
    }

}
