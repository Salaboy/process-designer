/***************************************
 * Copyright (c) Intalio, Inc 2010
 *
 * Permission is hereby granted, free of charge, to any person obtaining a
 * copy of this software and associated documentation files (the "Software"),
 * to deal in the Software without restriction, including without limitation
 * the rights to use, copy, modify, merge, publish, distribute, sublicense,
 * and/or sell copies of the Software, and to permit persons to whom the
 * Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
 * DEALINGS IN THE SOFTWARE.
 ****************************************/

package com.intalio.bpmn2;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.codehaus.jackson.JsonFactory;
import org.codehaus.jackson.JsonParseException;
import org.codehaus.jackson.JsonParser;
import org.codehaus.jackson.JsonToken;
import org.eclipse.bpmn2.Artifact;
import org.eclipse.bpmn2.Auditing;
import org.eclipse.bpmn2.BaseElement;
import org.eclipse.bpmn2.Bpmn2Factory;
import org.eclipse.bpmn2.BusinessRuleTask;
import org.eclipse.bpmn2.Definitions;
import org.eclipse.bpmn2.Documentation;
import org.eclipse.bpmn2.Event;
import org.eclipse.bpmn2.Expression;
import org.eclipse.bpmn2.FlowNode;
import org.eclipse.bpmn2.Gateway;
import org.eclipse.bpmn2.GlobalScriptTask;
import org.eclipse.bpmn2.GlobalTask;
import org.eclipse.bpmn2.Lane;
import org.eclipse.bpmn2.ManualTask;
import org.eclipse.bpmn2.Monitoring;
import org.eclipse.bpmn2.Process;
import org.eclipse.bpmn2.ProcessType;
import org.eclipse.bpmn2.RootElement;
import org.eclipse.bpmn2.ScriptTask;
import org.eclipse.bpmn2.SequenceFlow;
import org.eclipse.bpmn2.ServiceTask;
import org.eclipse.bpmn2.Task;
import org.eclipse.bpmn2.UserTask;
import org.eclipse.bpmn2.util.Bpmn2ResourceFactoryImpl;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;

/**
 * @author Antoine Toulme
 * 
 *         an unmarshaller to transform JSON into BPMN 2.0 elements.
 * 
 */
public class Bpmn2JsonUnmarshaller {

    // a list of the objects created, kept in memory with their original id for
    // fast lookup.
    private Map<Object, String> _idMap = new HashMap<Object, String>();

    // the collection of outgoing ids.
    // we reconnect the edges with the shapes as a last step of the construction
    // of our graph from json, as we miss elements before.
    private Map<Object, List<String>> _outgoingFlows = new HashMap<Object, List<String>>();
    private Set<String> _sequenceFlowTargets = new HashSet<String>();

    public Definitions unmarshall(String json) throws JsonParseException, IOException {
        return unmarshall(new JsonFactory().createJsonParser(json));
    }

    public Definitions unmarshall(File file) throws JsonParseException, IOException {
        return unmarshall(new JsonFactory().createJsonParser(file));
    }

    private Definitions unmarshall(JsonParser parser) throws JsonParseException, IOException {
        parser.nextToken(); // open the object
        ResourceSet rSet = new ResourceSetImpl();
        rSet.getResourceFactoryRegistry().getExtensionToFactoryMap().put("bpmn2",
                new Bpmn2ResourceFactoryImpl());
        Resource bpmn2 = rSet.createResource(URI.createURI("virtual.bpmn2"));
        rSet.getResources().add(bpmn2);
        Definitions def = (Definitions) unmarshallItem(parser);
        bpmn2.getContents().add(def);
        reconnectFlows();
        return def;
    }

    private void reconnectFlows() {
        // create the reverse id map:
        Map<String, Object> idToObjectMap = new HashMap<String, Object>();
        for (Entry<Object, String> entry : _idMap.entrySet()) {
            idToObjectMap.put(entry.getValue(), entry.getKey());
        }

        for (Entry<Object, List<String>> entry : _outgoingFlows.entrySet()) {

            for (String flowId : entry.getValue()) {
                if (entry.getKey() instanceof SequenceFlow) {
                    ((SequenceFlow) entry.getKey()).setTargetRef((FlowNode) idToObjectMap.get(flowId));
                } else {
                    ((FlowNode) entry.getKey()).getOutgoing().add((SequenceFlow) idToObjectMap.get(flowId));
                }

            }
        }
    }

    private BaseElement unmarshallItem(JsonParser parser) throws JsonParseException, IOException {

        String resourceId = null;
        Map<String, String> properties = null;
        String stencil = null;
        List<BaseElement> childElements = new ArrayList<BaseElement>();
        List<String> outgoing = new ArrayList<String>();
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            String fieldname = parser.getCurrentName();
            parser.nextToken();
            if ("resourceId".equals(fieldname)) {
                resourceId = parser.getText();
            } else if ("properties".equals(fieldname)) {
                properties = unmarshallProperties(parser);
            } else if ("stencil".equals(fieldname)) {
                // "stencil":{"id":"Task"},
                parser.nextToken();
                parser.nextToken();
                stencil = parser.getText();
                parser.nextToken();
            } else if ("childShapes".equals(fieldname)) {
                while (parser.nextToken() != JsonToken.END_ARRAY) { // open the
                                                                    // object
                    // the childShapes element is a json array. We opened the
                    // array.
                    childElements.add(unmarshallItem(parser));
                }
            } else if ("bounds".equals(fieldname)) {
                // pass on this
                parser.skipChildren();
            } else if ("dockers".equals(fieldname)) {
                // pass on this
                parser.skipChildren();
            } else if ("outgoing".equals(fieldname)) {
                while (parser.nextToken() != JsonToken.END_ARRAY) {
                    // {resourceId: oryx_1AAA8C9A-39A5-42FC-8ED1-507A7F3728EA}
                    parser.nextToken();
                    parser.nextToken();
                    outgoing.add(parser.getText());
                    parser.nextToken();
                }
                // pass on the array
                parser.skipChildren();
            } else if ("target".equals(fieldname)) {
                // we already collected that info with the outgoing field.
                parser.skipChildren();
                // "target": {
                // "resourceId": "oryx_A75E7546-DF71-48EA-84D3-2A8FD4A47568"
                // }
                // add to the map:
                // parser.nextToken(); // resourceId:
                // parser.nextToken(); // the value we want to save
                // targetId = parser.getText();
                // parser.nextToken(); // }, closing the object
            }
        }
        BaseElement baseElt = Bpmn20Stencil.createElement(stencil, properties.get("tasktype"));

        // register the sequence flow targets.
        if (baseElt instanceof SequenceFlow) {
            _sequenceFlowTargets.addAll(outgoing);
        }
        _outgoingFlows.put(baseElt, outgoing);
        _idMap.put(baseElt, resourceId); // keep the object around to do
                                         // connections.
        // baseElt.setId(resourceId); commented out as bpmn2 seems to create
        // duplicate ids right now.

        applyProperties(baseElt, properties);

        if (baseElt instanceof Definitions) {
            Process rootLevelProcess = null;
            for (BaseElement child : childElements) {

                // tasks are only permitted under processes.
                // a process should be created implicitly for tasks at the root
                // level.

                // process designer doesn't make a difference between tasks and
                // global tasks.
                // if a task has sequence edges it is considered a task,
                // otherwise it is considered a global task.
                if (child instanceof Task && _outgoingFlows.get(child).isEmpty() && !_sequenceFlowTargets.contains(_idMap.get(child))) {
                    // no edges on a task at the top level! We replace it with a
                    // global task.
                    GlobalTask task = null;
                    if (child instanceof ScriptTask) {
                        task = Bpmn2Factory.eINSTANCE.createGlobalScriptTask();
                        ((GlobalScriptTask) task).setScript(((ScriptTask) child).getScript());
                        ((GlobalScriptTask) task).setScriptLanguage(((ScriptTask) child).getScriptFormat()); // TODO
                                                                                                             // scriptLanguage
                                                                                                             // missing
                                                                                                             // on
                                                                                                             // scriptTask
                    } else if (child instanceof UserTask) {
                        task = Bpmn2Factory.eINSTANCE.createGlobalUserTask();
                    } else if (child instanceof ServiceTask) {
                        // we don't have a global service task! Fallback on a
                        // normal global task
                        task = Bpmn2Factory.eINSTANCE.createGlobalTask();
                    } else if (child instanceof ServiceTask) {
                        // we don't have a global service task! Fallback on a
                        // normal global task
                        task = Bpmn2Factory.eINSTANCE.createGlobalTask();
                    } else if (child instanceof BusinessRuleTask) {
                        task = Bpmn2Factory.eINSTANCE.createGlobalBusinessRuleTask();
                    } else if (child instanceof ManualTask) {
                        task = Bpmn2Factory.eINSTANCE.createGlobalManualTask();
                    } else {
                        task = Bpmn2Factory.eINSTANCE.createGlobalTask();
                    }

                    task.setName(((Task) child).getName());
                    task.setIoSpecification(((Task) child).getIoSpecification());
                    task.getDocumentation().addAll(((Task) child).getDocumentation());
                    ((Definitions) baseElt).getRootElements().add(task);
                    continue;
                } else {
                    if (child instanceof Task || child instanceof SequenceFlow || child instanceof Gateway || child instanceof Event || child instanceof Artifact) {
                        if (rootLevelProcess == null) {
                            rootLevelProcess = Bpmn2Factory.eINSTANCE.createProcess();
                            rootLevelProcess.setName(((Definitions) baseElt).getName());
                            ((Definitions) baseElt).getRootElements().add(rootLevelProcess);
                        }
                    }
                    if (child instanceof Task) {
                        // find the special process for root level tasks:
                        rootLevelProcess.getFlowElements().add((Task) child);
                    } else if (child instanceof RootElement) {
                        ((Definitions) baseElt).getRootElements().add((RootElement) child);
                    } else if (child instanceof SequenceFlow) {
                        // find the special process for root level tasks:
                        rootLevelProcess.getFlowElements().add((SequenceFlow) child);
                    } else if (child instanceof Gateway) {
                        rootLevelProcess.getFlowElements().add((Gateway) child);
                    } else if (child instanceof Event) {
                        rootLevelProcess.getFlowElements().add((Event) child);
                    } else if (child instanceof Artifact) {
                        rootLevelProcess.getArtifacts().add((Artifact) child);
                    }
                }
            }
        } else if (baseElt instanceof Process) {
            for (BaseElement child : childElements) {
                if (child instanceof Lane) {
                    if (((Process) baseElt).getLaneSets().isEmpty()) {
                        ((Process) baseElt).getLaneSets().add(Bpmn2Factory.eINSTANCE.createLaneSet());
                    }
                    ((Process) baseElt).getLaneSets().get(0).getLanes().add((Lane) child);
                } else if (child instanceof Artifact) {
                    ((Process) baseElt).getArtifacts().add((Artifact) child);
                }
            }
        }
        return baseElt;
    }

    private void applyProperties(BaseElement baseElement, Map<String, String> properties) {
        applyBaseElementProperties((BaseElement) baseElement, properties);
        if (baseElement instanceof GlobalTask) {
            applyGlobalTaskProperties((GlobalTask) baseElement, properties);
        }
        if (baseElement instanceof Definitions) {
            applyDefinitionProperties((Definitions) baseElement, properties);
        }
        if (baseElement instanceof Process) {
            applyProcessProperties((Process) baseElement, properties);
        }
        if (baseElement instanceof Lane) {
            applyLaneProperties((Lane) baseElement, properties);
        }
        if (baseElement instanceof SequenceFlow) {
            applySequenceFlowProperties((SequenceFlow) baseElement, properties);
        }
        if (baseElement instanceof Task) {
            applyTaskProperties((Task) baseElement, properties);
        }
        if (baseElement instanceof ScriptTask) {
            applyScriptTaskProperties((ScriptTask) baseElement, properties);
        }
        if (baseElement instanceof Gateway) {
            applyGatewayProperties((Gateway) baseElement, properties);
        }
        if (baseElement instanceof Event) {
            applyEventProperties((Event) baseElement, properties);
        }
    }

    private void applyEventProperties(Event event, Map<String, String> properties) {
        event.setName(properties.get("name"));
        if (properties.get("auditing") != null && !"".equals(properties.get("auditing"))) {
            Auditing audit = Bpmn2Factory.eINSTANCE.createAuditing();
            audit.getDocumentation().add(createDocumentation(properties.get("auditing")));
            event.setAuditing(audit);
        }
        if (properties.get("monitoring") != null && !"".equals(properties.get("monitoring"))) {
            Monitoring monitoring = Bpmn2Factory.eINSTANCE.createMonitoring();
            monitoring.getDocumentation().add(createDocumentation(properties.get("monitoring")));
            event.setMonitoring(monitoring);
        }
    }

    private void applyGlobalTaskProperties(GlobalTask globalTask, Map<String, String> properties) {
        globalTask.setName(properties.get("name"));
        // InputOutputSpecification ioSpec =
        // Bpmn2Factory.eINSTANCE.createInputOutputSpecification();
        // ioSpec.get
        // globalTask.setIoSpecification(value)
    }

    private void applyBaseElementProperties(BaseElement baseElement, Map<String, String> properties) {
        if (properties.get("documentation") != null && !"".equals(properties.get("documentation"))) {
            baseElement.getDocumentation().add(createDocumentation(properties.get("documentation")));
        }
    }

    private void applyDefinitionProperties(Definitions def, Map<String, String> properties) {
        def.setTypeLanguage(properties.get("typelanguage"));
        def.setTargetNamespace(properties.get("targetnamespace"));
        def.setExpressionLanguage(properties.get("expressionlanguage"));
        def.setName(properties.get("name"));
    }

    private void applyProcessProperties(Process process, Map<String, String> properties) {
        process.setName(properties.get("name"));
        if (properties.get("auditing") != null && !"".equals(properties.get("auditing"))) {
            Auditing audit = Bpmn2Factory.eINSTANCE.createAuditing();
            audit.getDocumentation().add(createDocumentation(properties.get("auditing")));
            process.setAuditing(audit);
        }
        process.setProcessType(ProcessType.getByName(properties.get("processtype")));
        process.setIsClosed(Boolean.parseBoolean(properties.get("isclosed")));
    }

    private void applyScriptTaskProperties(ScriptTask scriptTask, Map<String, String> properties) {
        scriptTask.setName(properties.get("name"));
        scriptTask.setScript(properties.get("script"));
        scriptTask.setScriptFormat(properties.get("script_language"));
    }

    private void applyLaneProperties(Lane lane, Map<String, String> properties) {
        lane.setName(properties.get("name"));
    }

    private void applyTaskProperties(Task task, Map<String, String> properties) {
        task.setName(properties.get("name"));
    }
    
    private void applyGatewayProperties(Gateway gateway, Map<String, String> properties) {
        gateway.setName(properties.get("name"));
    }

    private void applySequenceFlowProperties(SequenceFlow sequenceFlow, Map<String, String> properties) {
        sequenceFlow.setName(properties.get("name"));
        if (properties.get("auditing") != null && !"".equals(properties.get("auditing"))) {
            Auditing audit = Bpmn2Factory.eINSTANCE.createAuditing();
            audit.getDocumentation().add(createDocumentation(properties.get("auditing")));
            sequenceFlow.setAuditing(audit);
        }
        if (properties.get("conditionexpression") != null && !"".equals(properties.get("conditionexpression"))) {
            Expression expr = Bpmn2Factory.eINSTANCE.createExpression();
            expr.getDocumentation().add(createDocumentation(properties.get("conditionexpression")));
            sequenceFlow.setConditionExpression(expr);
        }
        if (properties.get("monitoring") != null && !"".equals(properties.get("monitoring"))) {
            Monitoring monitoring = Bpmn2Factory.eINSTANCE.createMonitoring();
            monitoring.getDocumentation().add(createDocumentation(properties.get("monitoring")));
            sequenceFlow.setMonitoring(monitoring);
        }
        sequenceFlow.setIsImmediate(Boolean.parseBoolean(properties.get("isimmediate")));
    }

    private Map<String, String> unmarshallProperties(JsonParser parser) throws JsonParseException, IOException {
        Map<String, String> properties = new HashMap<String, String>();
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            String fieldname = parser.getCurrentName();
            parser.nextToken();
            properties.put(fieldname, parser.getText());
        }
        return properties;
    }

    private Documentation createDocumentation(String text) {
        Documentation doc = Bpmn2Factory.eINSTANCE.createDocumentation();
        doc.setText(text);
        return doc;
    }
}