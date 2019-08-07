/*
 * Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"). You may not use this file except in compliance
 * with the License. A copy of the License is located at
 *
 * http://aws.amazon.com/apache2.0/
 *
 * or in the "license" file accompanying this file. This file is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES
 * OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 */
package org.apache.mxnet.engine;

import com.sun.jna.Pointer;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.mxnet.jna.JnaUtils;
import software.amazon.ai.util.Utils;

public class Symbol extends NativeResource {

    //    private String[] argParams;
    //    private String[] auxParams;
    private String[] outputs;
    //    private List<Integer> outputLayouts;
    private MxNDManager manager;

    Symbol(MxNDManager manager, Pointer pointer) {
        super(pointer);
        this.manager = manager;
        manager.attach(this);
        //        argParams = JnaUtils.listSymbolArguments(getHandle());
        //        auxParams = JnaUtils.listSymbolAuxiliaryStates(getHandle());
    }

    public static Symbol load(MxNDManager manager, String path) {
        Pointer pointer = JnaUtils.createSymbolFromFile(path);
        return new Symbol(manager, pointer);
    }

    /*
    public String[] getArgParams() {
        return argParams;
    }

    public String[] getAuxParams() {
        return auxParams;
    }

     */

    public String[] getAllNames() {
        return JnaUtils.listSymbolNames(getHandle());
    }

    public String[] getOutputs() {
        if (outputs == null) {
            outputs = JnaUtils.listSymbolOutputs(getInternals().getHandle());
        }
        return outputs;
    }

    /*
    public List<Integer> getOutputLayouts() {
        if (outputLayouts == null) {
            outputLayouts = new ArrayList<>();
            for (String argName : getArgParams()) {
                try (Symbol symbol = get(argName)) {
                    Layout layout = Layout.fromValue(symbol.getAttribute("__layout__"));
                    outputLayouts.add(DataDesc.getBatchAxis(layout));
                }
            }
        }
        return outputLayouts;
    }

    public String getAttribute(String key) {
        return JnaUtils.getSymbolAttr(getHandle(), key);
    }

    public PairList<String, String> getAttributes() {
        return JnaUtils.listSymbolAttr(getHandle());
    }

     */

    public Symbol copy() {
        return this;
    }

    public Symbol get(int index) {
        Pointer pointer = JnaUtils.getSymbolOutput(getInternals().getHandle(), index);
        return new Symbol(manager, pointer);
    }

    public Symbol get(String name) {
        String[] out = getOutputs();
        int index = Utils.indexOf(out, name);
        if (index < 0) {
            throw new IllegalArgumentException("Cannot find output that matches name: " + name);
        }
        return get(index);
    }

    public Symbol getInternals() {
        Pointer pointer = JnaUtils.getSymbolInternals(getHandle());
        return new Symbol(manager, pointer);
    }

    public List<String> getLayerNames() {
        String[] outputNames = getOutputs();
        String[] allNames = getAllNames();
        Set<String> allNamesSet = new LinkedHashSet<>(Arrays.asList(allNames));
        // Kill all params field and keep the output layer
        return Arrays.stream(outputNames)
                .filter(n -> !allNamesSet.contains(n))
                .collect(Collectors.toList());
    }

    /*

    public String debugStr() {
        return JnaUtils.getSymbolDebugString(getHandle());
    }

    public void setAttr(Map<String, String> attrs) {
        for (Map.Entry<String, String> entry : attrs.entrySet()) {
            JnaUtils.setSymbolAttr(getHandle(), entry.getKey(), entry.getValue());
        }
    }

    public PairList<String, String> listAttr() {
        return JnaUtils.listSymbolAttr(getHandle());
    }

    public PairList<String, String> attrMap() {
        return JnaUtils.listSymbolAttr(getHandle());
    }

    public void save(String path) {
        JnaUtils.saveSymbol(getHandle(), path);
    }

    public Symbol compose(String name, String[] keys) {
        return new Symbol(manager, JnaUtils.compose(getHandle(), name, keys));
    }

    public void compose(String name, Map<String, String> symbols) {
        JnaUtils.compose(getHandle(), name, symbols.values().toArray(JnaUtils.EMPTY_ARRAY));
    }

    public String toJson() {
        return JnaUtils.symbolToJson(getHandle());
    }

     */

    /** {@inheritDoc} */
    @Override
    public void close() {
        Pointer pointer = handle.getAndSet(null);
        if (pointer != null) {
            JnaUtils.freeSymbol(pointer);
        }
    }
}
