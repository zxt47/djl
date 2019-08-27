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
package org.apache.mxnet.jna;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.PointerByReference;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.StreamSupport;
import org.apache.mxnet.engine.CachedOp;
import org.apache.mxnet.engine.DeviceType;
import org.apache.mxnet.engine.MxNDArray;
import org.apache.mxnet.engine.MxNDManager;
import org.apache.mxnet.engine.Symbol;
import org.apache.mxnet.engine.SymbolBlock;
import software.amazon.ai.Context;
import software.amazon.ai.engine.EngineException;
import software.amazon.ai.ndarray.NDArray;
import software.amazon.ai.ndarray.NDList;
import software.amazon.ai.ndarray.types.DataType;
import software.amazon.ai.ndarray.types.Shape;
import software.amazon.ai.ndarray.types.SparseFormat;
import software.amazon.ai.util.Pair;
import software.amazon.ai.util.PairList;

public final class JnaUtils {

    public static final String[] EMPTY_ARRAY = new String[0];

    private static final String[] OP_NAME_PREFIX = {
        "_contrib_", "_linalg_", "_sparse_", "_image_", "_random_"
    };

    private static final MxnetLibrary LIB = LibUtils.loadLibrary();

    private static final Map<String, FunctionInfo> OPS = getNdArrayFunctions();

    private JnaUtils() {}

    /////////////////////////////////
    // MXNet information
    /////////////////////////////////

    public static int getVersion() {
        IntBuffer version = IntBuffer.allocate(1);
        checkCall(LIB.MXGetVersion(version));

        return version.get();
    }

    public static Set<String> getAllOpNames() {
        IntBuffer outSize = IntBuffer.allocate(1);
        PointerByReference outArray = new PointerByReference();

        checkCall(LIB.MXListAllOpNames(outSize, outArray));

        int size = outSize.get();
        Pointer[] pointers = outArray.getValue().getPointerArray(0, size);

        Set<String> set = new HashSet<>();
        for (Pointer p : pointers) {
            set.add(p.getString(0, StandardCharsets.UTF_8.name()));
        }
        return set;
    }

    public static Map<String, FunctionInfo> getNdArrayFunctions() {
        Set<String> opNames = JnaUtils.getAllOpNames();
        Map<String, FunctionInfo> map = new ConcurrentHashMap<>();

        for (String opName : opNames) {
            PointerByReference ref = new PointerByReference();
            checkCall(LIB.NNGetOpHandle(opName, ref));

            String functionName = getOpNamePrefix(opName);

            // System.out.println("Name: " + opName + "/" + functionName);
            map.put(functionName, getFunctionByName(opName, functionName, ref.getValue()));
        }
        return map;
    }

    public static FunctionInfo op(String opName) {
        if (!OPS.containsKey(opName)) {
            throw new IllegalArgumentException("Unknown operator: " + opName);
        }
        return OPS.get(opName);
    }

    private static FunctionInfo getFunctionByName(
            String name, String functionName, Pointer handle) {
        String[] nameRef = {name};
        String[] description = new String[1];
        IntBuffer numArgs = IntBuffer.allocate(1);
        PointerByReference argNameRef = new PointerByReference();
        PointerByReference argTypeRef = new PointerByReference();
        PointerByReference argDescRef = new PointerByReference();
        String[] keyVarArgs = new String[1];
        String[] returnType = new String[1];

        checkCall(
                LIB.MXSymbolGetAtomicSymbolInfo(
                        handle,
                        nameRef,
                        description,
                        numArgs,
                        argNameRef,
                        argTypeRef,
                        argDescRef,
                        keyVarArgs,
                        returnType));

        int count = numArgs.get();
        PairList<String, String> arguments = new PairList<>();
        if (count != 0) {
            String[] argNames =
                    argNameRef.getValue().getStringArray(0, count, StandardCharsets.UTF_8.name());
            String[] argTypes =
                    argTypeRef.getValue().getStringArray(0, count, StandardCharsets.UTF_8.name());
            for (int i = 0; i < argNames.length; i++) {
                arguments.add(argNames[i], argTypes[i]);
            }
        }

        return new FunctionInfo(handle, functionName, arguments);
    }

    /*
    int MXFuncGetInfo(Pointer fun, String name[], String description[], IntBuffer num_args,
                      PointerByReference arg_names, PointerByReference arg_type_infos,
                      PointerByReference arg_descriptions, String return_type[]);

    int MXFuncDescribe(Pointer fun, IntBuffer num_use_vars, IntBuffer num_scalars,
                       IntBuffer num_mutate_vars, IntBuffer type_mask);

    int MXFuncInvoke(Pointer fun, PointerByReference use_vars, FloatBuffer scalar_args,
                     PointerByReference mutate_vars);

    int MXFuncInvokeEx(Pointer fun, PointerByReference use_vars, FloatBuffer scalar_args,
                       PointerByReference mutate_vars, int num_params,
                       PointerByReference param_keys, PointerByReference param_vals);
    */

    /////////////////////////////////
    // System information
    /////////////////////////////////

    public static int getGpuCount() {
        IntBuffer count = IntBuffer.allocate(1);
        checkCall(LIB.MXGetGPUCount(count));

        return count.get();
    }

    public static long[] getGpuMemory(Context context) {
        if (!Context.gpu().getDeviceType().equals(context.getDeviceType())) {
            throw new IllegalArgumentException("Only GPU context is allowed.");
        }

        int deviceId = context.getDeviceId();
        long[] ret = new long[2];

        LongBuffer freeMem = LongBuffer.wrap(ret, 0, 1);
        LongBuffer totalMem = LongBuffer.wrap(ret, 1, 1);

        checkCall(LIB.MXGetGPUMemoryInformation64(deviceId, freeMem, totalMem));

        return ret;
    }

    /* Need tests
    public static void setOmpThreads(int threads) {
        checkCall(LIB.MXSetNumOMPThreads(threads));
    }

    public static int setBulkSize(int bulkSize) {
        IntBuffer prevBulkSize = IntBuffer.allocate(1);
        checkCall(LIB.MXEngineSetBulkSize(bulkSize, prevBulkSize));

        return prevBulkSize.get();
    }
    */

    /////////////////////////////////
    // Utilities
    /////////////////////////////////

    /* Need tests
    public static int randomSeed(int seed) {
        return LIB.MXRandomSeed(seed);
    }

    public static int randomSeed(int seed, Context context) {
        int deviceType = DeviceType.toDeviceType(context);
        return LIB.MXRandomSeedContext(seed, deviceType, context.getDeviceId());
    }

    public static void notifyShutdown() {
        checkCall(LIB.MXNotifyShutdown());
    }
    */

    /////////////////////////////////
    // Profiler information
    /////////////////////////////////

    /*
    public static int setProcessProfilerConfig(int numParams, String keys[], String vals[],
                                               Pointer kvstoreHandle) {

    }

    int MXSetProfilerConfig(int num_params, String keys[], String vals[]);

    int MXSetProcessProfilerState(int state, int profile_process, Pointer kvStoreHandle);

    int MXSetProfilerState(int state);

    int MXDumpProcessProfile(int finished, int profile_process, Pointer kvStoreHandle);

    int MXDumpProfile(int finished);

    int MXAggregateProfileStatsPrint(String out_str[], int reset);

    int MXProcessProfilePause(int paused, int profile_process, Pointer kvStoreHandle);

    int MXProfilePause(int paused);

    int MXProfileCreateDomain(String domain, PointerByReference out);

    int MXProfileCreateTask(Pointer domain, Pointer task_name, PointerByReference out);

    int MXProfileCreateTask(Pointer domain, String task_name, PointerByReference out);

    int MXProfileCreateFrame(Pointer domain, String frame_name, PointerByReference out);

    int MXProfileCreateEvent(String event_name, PointerByReference out);

    int MXProfileCreateCounter(Pointer domain, String counter_name, PointerByReference out);

    int MXProfileDestroyHandle(Pointer frame_handle);

    int MXProfileDurationStart(Pointer duration_handle);

    int MXProfileDurationStop(Pointer duration_handle);

    int MXProfileSetCounter(Pointer counter_handle, long value);

    int MXProfileAdjustCounter(Pointer counter_handle, long value);

    int MXProfileSetMarker(Pointer domain, String instant_marker_name, String scope);
    */

    /////////////////////////////////
    // NDArray
    /////////////////////////////////

    /* Need tests
    public static Pointer createNdArray() {
        PointerByReference ref = new PointerByReference();
        checkCall(LIB.MXNDArrayCreateNone(ref));

        return ref.getValue();
    }
     */

    public static Pointer createNdArray(
            Context context, Shape shape, DataType dtype, int size, boolean delayedAlloc) {
        int deviceType = DeviceType.toDeviceType(context);
        int deviceId = context.getDeviceId();
        int delay = delayedAlloc ? 1 : 0;

        PointerByReference ref = new PointerByReference();
        int[] shapeArray = Arrays.stream(shape.getShape()).mapToInt(Math::toIntExact).toArray();
        checkCall(
                LIB.MXNDArrayCreateEx(
                        shapeArray, size, deviceType, deviceId, delay, dtype.ordinal(), ref));

        return ref.getValue();
    }

    public static Pointer createSparseNdArray(
            SparseFormat fmt,
            Context context,
            Shape shape,
            DataType dtype,
            DataType[] auxDTypes,
            Shape[] auxShapes,
            boolean delayedAlloc) {
        int[] shapeArray = Arrays.stream(shape.getShape()).mapToInt(Math::toIntExact).toArray();
        int deviceType = DeviceType.toDeviceType(context);
        int deviceId = context.getDeviceId();
        int delay = delayedAlloc ? 1 : 0;
        PointerByReference ref = new PointerByReference();
        IntBuffer auxDTypesInt =
                IntBuffer.wrap(Arrays.stream(auxDTypes).mapToInt(DataType::ordinal).toArray());
        IntBuffer auxNDims =
                IntBuffer.wrap(Arrays.stream(auxShapes).mapToInt(Shape::dimension).toArray());
        int[] auxShapesInt = Arrays.stream(auxShapes).mapToInt(ele -> (int) ele.head()).toArray();
        checkCall(
                LIB.MXNDArrayCreateSparseEx(
                        fmt.getValue(),
                        shapeArray,
                        shapeArray.length,
                        deviceType,
                        deviceId,
                        delay,
                        dtype.ordinal(),
                        auxDTypes.length,
                        auxDTypesInt,
                        auxNDims,
                        auxShapesInt,
                        ref));
        return ref.getValue();
    }

    public static void ndArraySyncCopyFromNdArray(MxNDArray dest, MxNDArray src, int location) {
        checkCall(LIB.MXNDArraySyncCopyFromNDArray(dest.getHandle(), src.getHandle(), location));
    }

    /* Need tests
    public static Pointer loadFromBytes(byte[] buf, int offset, int size) {
        Memory memory = new Memory(size);
        memory.write(0, buf, offset, size);

        PointerByReference ref = new PointerByReference();
        checkCall(LIB.MXNDArrayLoadFromRawBytes(memory, new NativeSize(size), ref));

        return ref.getValue();
    }

    public static void saveNdArray(String file, Pointer[] ndArrays, String[] keys) {
        PointerArray array = new PointerArray(ndArrays);
        checkCall(LIB.MXNDArraySave(file, ndArrays.length, array, keys));
    }
     */

    public static NDList loadNdArray(MxNDManager manager, Path path) {
        IntBuffer handlesSize = IntBuffer.allocate(1);
        PointerByReference handlesRef = new PointerByReference();
        PointerByReference namesRef = new PointerByReference();
        IntBuffer namesSize = IntBuffer.allocate(1);
        checkCall(LIB.MXNDArrayLoad(path.toString(), handlesSize, handlesRef, namesSize, namesRef));
        int ndArrayCount = handlesSize.get();
        int nameCount = namesSize.get();
        if (nameCount > 0 && ndArrayCount != nameCount) {
            throw new IllegalStateException(
                    "Mismatch between names and arrays in checkpoint file: " + path.toString());
        }
        Pointer[] handles = handlesRef.getValue().getPointerArray(0, ndArrayCount);
        NDList ndList = new NDList();
        if (nameCount == 0) {
            for (Pointer handle : handles) {
                ndList.add(manager.create(handle));
            }
        } else {
            assert namesRef.getValue() != null;
            String[] names = namesRef.getValue().getStringArray(0, nameCount);
            for (int i = 0; i < ndArrayCount; i++) {
                ndList.add(names[i], manager.create(handles[i]));
            }
        }
        return ndList;
    }

    public static void saveNdArray(Path path, NDList ndList) {
        IntBuffer handlesSize = IntBuffer.allocate(1);
        handlesSize.put(ndList.size());
        boolean namesProvided =
                StreamSupport.stream(ndList.spliterator(), false)
                        .map((Pair<String, NDArray> x) -> x.getKey())
                        .allMatch((String x) -> x != null);
        Pointer[] handles =
                StreamSupport.stream(ndList.spliterator(), false)
                        .map(
                                (Pair<String, NDArray> x) -> {
                                    MxNDArray ary = (MxNDArray) x.getValue();
                                    return ary.getHandle();
                                })
                        .toArray(Pointer[]::new);
        String[] names =
                StreamSupport.stream(ndList.spliterator(), false)
                        .map((Pair<String, NDArray> x) -> x.getKey())
                        .toArray(String[]::new);
        if (!namesProvided) {
            names = null;
        }
        checkCall(
                LIB.MXNDArraySave(
                        path.toString(), ndList.size(), new PointerArray(handles), names));
    }

    /* Need tests
    public static ByteBuffer readBytes(Pointer ndArray) {
        NativeSizeByReference size = new NativeSizeByReference();
        PointerByReference ref = new PointerByReference();
        checkCall(LIB.MXNDArraySaveRawBytes(ndArray, size, ref));

        return ref.getValue().getByteBuffer(0, size.getValue().longValue());
    }
     */

    public static void freeNdArray(Pointer ndArray) {
        checkCall(LIB.MXNDArrayFree(ndArray));
    }

    public static void waitToRead(Pointer ndArray) {
        checkCall(LIB.MXNDArrayWaitToRead(ndArray));
    }

    public static void waitToWrite(Pointer ndArray) {
        checkCall(LIB.MXNDArrayWaitToWrite(ndArray));
    }

    /* Need tests
    public static void waitAll() {
        checkCall(LIB.MXNDArrayWaitAll());
    }
     */

    public static void syncCopyToCPU(Pointer ndArray, Pointer data, int len) {
        NativeSize size = new NativeSize(len);
        checkCall(LIB.MXNDArraySyncCopyToCPU(ndArray, data, size));
    }

    public static void syncCopyFromCPU(Pointer ndArray, Buffer data, int len) {
        NativeSize size = new NativeSize(len);
        Pointer pointer = Native.getDirectBufferPointer(data);
        checkCall(LIB.MXNDArraySyncCopyFromCPU(ndArray, pointer, size));
    }

    public static PairList<Pointer, SparseFormat> imperativeInvoke(
            Pointer function,
            PointerArray inputs,
            PointerByReference destRef,
            PairList<String, ?> params) {
        String[] keys;
        String[] values;
        if (params == null) {
            keys = EMPTY_ARRAY;
            values = EMPTY_ARRAY;
        } else {
            keys = params.keyArray(EMPTY_ARRAY);
            values = params.values().stream().map(Object::toString).toArray(String[]::new);
        }
        PointerByReference destSType = new PointerByReference();
        IntBuffer numOutputs = IntBuffer.allocate(1);
        numOutputs.put(0, 1);

        checkCall(
                LIB.MXImperativeInvokeEx(
                        function,
                        inputs.numElements(),
                        inputs,
                        numOutputs,
                        destRef,
                        keys.length,
                        keys,
                        values,
                        destSType));
        int numOfOutputs = numOutputs.get(0);
        Pointer[] ptrArray = destRef.getValue().getPointerArray(0, numOfOutputs);
        int[] sTypes = destSType.getValue().getIntArray(0, numOfOutputs);
        PairList<Pointer, SparseFormat> pairList = new PairList<>();
        for (int i = 0; i < numOfOutputs; i++) {
            pairList.add(ptrArray[i], SparseFormat.fromValue(sTypes[i]));
        }
        return pairList;
    }

    public static SparseFormat getStorageType(Pointer ndArray) {
        IntBuffer type = IntBuffer.allocate(1);
        checkCall(LIB.MXNDArrayGetStorageType(ndArray, type));
        return SparseFormat.fromValue(type.get());
    }

    public static Context getContext(Pointer ndArray) {
        IntBuffer deviceType = IntBuffer.allocate(1);
        IntBuffer deviceId = IntBuffer.allocate(1);
        checkCall(LIB.MXNDArrayGetContext(ndArray, deviceType, deviceId));
        return new Context(DeviceType.fromDeviceType(deviceType.get(0)), deviceId.get(0));
    }

    public static Shape getShape(Pointer ndArray) {
        IntBuffer dim = IntBuffer.allocate(1);
        PointerByReference ref = new PointerByReference();
        checkCall(LIB.MXNDArrayGetShapeEx(ndArray, dim, ref));
        int nDim = dim.get();
        // scalar case TODO(Jake) it should be -1 when numpy flag is on
        if (nDim == 0) {
            return new Shape();
        }
        int[] shape = ref.getValue().getIntArray(0, nDim);
        return new Shape(Arrays.stream(shape).asLongStream().toArray());
    }

    public static DataType getDataType(Pointer ndArray) {
        IntBuffer dataType = IntBuffer.allocate(1);
        checkCall(LIB.MXNDArrayGetDType(ndArray, dataType));
        return DataType.values()[dataType.get()];
    }

    /* Need tests
    public static DataType getAuxType(Pointer ndArray, int index) {
        IntBuffer dataType = IntBuffer.allocate(1);
        checkCall(LIB.MXNDArrayGetAuxType(ndArray, index, dataType));
        return DataType.values()[dataType.get()];
    }

    public static Pointer getAuxNdArray(Pointer ndArray, int index) {
        PointerByReference ref = new PointerByReference();
        checkCall(LIB.MXNDArrayGetAuxNDArray(ndArray, index, ref));
        return ref.getValue();
    }

    public static Pointer getDataNdArray(Pointer ndArray) {
        PointerByReference ref = new PointerByReference();
        checkCall(LIB.MXNDArrayGetDataNDArray(ndArray, ref));
        return ref.getValue();
    }

    public static Pointer getGrad(Pointer ndArray) {
        PointerByReference ref = new PointerByReference();
        checkCall(LIB.MXNDArrayGetGrad(ndArray, ref));
        return ref.getValue();
    }
     */

    public static Pointer ndArrayAt(Pointer ndArray, int index) {
        PointerByReference ref = new PointerByReference();
        checkCall(LIB.MXNDArrayAt(ndArray, index, ref));
        return ref.getValue();
    }

    public static Pointer slice(Pointer ndArray, int begin, int end) {
        PointerByReference ref = new PointerByReference();
        checkCall(LIB.MXNDArraySlice(ndArray, begin, end, ref));
        return ref.getValue();
    }

    public static Pointer reshape(Pointer ndArray, long[] dims, boolean reverse) {
        PointerByReference ref = new PointerByReference();
        byte reverseByte = reverse ? (byte) 1 : 0;
        checkCall(
                LIB.MXNDArrayReshape64(
                        ndArray, dims.length, LongBuffer.wrap(dims), reverseByte, ref));
        return ref.getValue();
    }

    /////////////////////////////////
    // MxGradientCollector
    /////////////////////////////////
    public static boolean autogradSetIsRecording(boolean isRecording) {
        IntBuffer prev = IntBuffer.allocate(1);
        checkCall(LIB.MXAutogradSetIsRecording(isRecording ? 1 : 0, prev));
        return prev.get(0) == 1;
    }

    public static boolean autogradSetTraining(boolean isTraining) {
        IntBuffer prev = IntBuffer.allocate(1);
        checkCall(LIB.MXAutogradSetIsTraining(isTraining ? 1 : 0, prev));
        return prev.get(0) == 1;
    }

    public static boolean autogradIsRecording() {
        ByteBuffer isRecording = ByteBuffer.allocate(1);
        checkCall(LIB.MXAutogradIsRecording(isRecording));
        return isRecording.get(0) == 1;
    }

    public static boolean autogradIsTraining() {
        ByteBuffer isTraining = ByteBuffer.allocate(1);
        checkCall(LIB.MXAutogradIsTraining(isTraining));
        return isTraining.get(0) == 1;
    }

    public static void autogradMarkVariables(
            int numVar, Pointer varHandles, IntBuffer reqsArray, Pointer gradHandles) {
        PointerByReference varRef = new PointerByReference(varHandles);
        PointerByReference gradRef = new PointerByReference(gradHandles);
        checkCall(LIB.MXAutogradMarkVariables(numVar, varRef, reqsArray, gradRef));
    }

    public static void autogradBackwardExecute(
            int numOutput,
            Pointer outputHandle,
            Pointer outputGradHandle,
            int numVariables,
            Pointer varHandles,
            int retainGraph,
            int createGraph,
            int isTrain,
            Pointer gradHandles,
            Pointer gradSparseFormat) {
        PointerByReference outRef = new PointerByReference(outputHandle);
        PointerByReference outGradRef = new PointerByReference(outputGradHandle);
        PointerByReference varRef = new PointerByReference(varHandles);
        PointerByReference gradRef = new PointerByReference(gradHandles);
        PointerByReference gradSparseFormatRef = new PointerByReference(gradSparseFormat);
        checkCall(
                LIB.MXAutogradBackwardEx(
                        numOutput,
                        outRef,
                        outGradRef,
                        numVariables,
                        varRef,
                        retainGraph,
                        createGraph,
                        isTrain,
                        gradRef,
                        gradSparseFormatRef));
    }

    public static boolean isNumpyMode() {
        ByteBuffer ret = ByteBuffer.allocate(1);
        checkCall(LIB.MXIsNumpyShape(ret));
        return ret.get() == 1;
    }

    public static void setNumpyMode(boolean numpy) {
        IntBuffer ret = IntBuffer.allocate(1);
        checkCall(LIB.MXSetIsNumpyShape(numpy ? 1 : 0, ret));
    }

    public static Pointer getGradient(Pointer handle) {
        PointerByReference ref = new PointerByReference();
        checkCall(LIB.MXNDArrayGetGrad(handle, ref));
        return ref.getValue();
    }

    /*
    int MXImperativeInvokeEx(Pointer creator, int num_inputs, PointerByReference inputs,
                             IntBuffer num_outputs, PointerByReference outputs, int num_params,
                             String param_keys[], String param_vals[],
                             PointerByReference out_stypes);

    int MXNDArraySyncCopyFromCPU(Pointer handle, Pointer data, NativeSize size);

    int MXNDArraySyncCopyFromNDArray(Pointer handle_dst, Pointer handle_src, int i);

    int MXNDArraySyncCheckFormat(Pointer handle, byte full_check);


    int MXNDArrayReshape(Pointer handle, int ndim, IntBuffer dims, PointerByReference out);

    int MXNDArrayReshape64(Pointer handle, int ndim, LongBuffer dims, byte reverse,
                           PointerByReference out);

    int MXNDArrayGetData(Pointer handle, PointerByReference out_pdata);

    int MXNDArrayToDLPack(Pointer handle, PointerByReference out_dlpack);

    int MXNDArrayFromDLPack(Pointer dlpack, PointerByReference out_handle);

    int MXNDArrayCallDLPackDeleter(Pointer dlpack);

    int MXNDArrayGetDType(Pointer handle, IntBuffer out_dtype);

    int MXNDArrayGetAuxType(Pointer handle, int i, IntBuffer out_type);

    int MXNDArrayGetAuxNDArray(Pointer handle, int i, PointerByReference out);

    int MXNDArrayGetDataNDArray(Pointer handle, PointerByReference out);

    int MXNDArrayGetContext(Pointer handle, IntBuffer out_dev_type, IntBuffer out_dev_id);

    int MXNDArrayDetach(Pointer handle, PointerByReference out);

    int MXNDArraySetGradState(Pointer handle, int state);

    int MXNDArrayGetGradState(Pointer handle, IntBuffer out);

    int MXListFunctions(IntBuffer out_size, PointerByReference out_array);


    int MXAutogradComputeGradient(int num_output, PointerByReference output_handles);

    int MXAutogradBackward(int num_output, PointerByReference output_handles,
                           PointerByReference ograd_handles, int retain_graph);


    int MXAutogradGetSymbol(Pointer handle, PointerByReference out);


    int MXCreateCachedOp(Pointer handle, PointerByReference out);


    int MXCreateCachedOpEx(Pointer handle, int num_flags, String keys[], String vals[],
                           PointerByReference out);


    int MXFreeCachedOp(Pointer handle);


    int MXInvokeCachedOp(Pointer handle, int num_inputs, PointerByReference inputs,
                         IntBuffer num_outputs, PointerByReference outputs);

    int MXInvokeCachedOpEx(Pointer handle, int num_inputs, PointerByReference inputs,
                           IntBuffer num_outputs, PointerByReference outputs,
                           PointerByReference out_stypes);


    int MXListAllOpNames(IntBuffer out_size, PointerByReference out_array);
    */

    /////////////////////////////////
    // MXNet Symbols
    /////////////////////////////////

    public static Pointer getSymbolOutput(Pointer symbol, int index) {
        PointerByReference ref = new PointerByReference();
        checkCall(LIB.MXSymbolGetOutput(symbol, index, ref));
        return ref.getValue();
    }

    public static String[] listSymbolOutputs(Pointer symbol) {
        IntBuffer size = IntBuffer.allocate(1);
        PointerByReference ref = new PointerByReference();

        checkCall(LIB.MXSymbolListOutputs(symbol, size, ref));
        return toStringArray(ref, size.get());
    }

    /* Need tests
    public static String symbolToJson(Pointer symbol) {
        String[] out = new String[1];
        checkCall(LIB.MXSymbolSaveToJSON(symbol, out));
        return out[0];
    }
     */

    public static void freeSymbol(Pointer symbol) {
        checkCall(LIB.MXSymbolFree(symbol));
    }

    /* Need tests
    public static void saveSymbol(Pointer symbol, String path) {
        checkCall(LIB.MXSymbolSaveToFile(symbol, path));
    }

    public static Pointer copySymbol(Pointer symbol) {
        PointerByReference ref = new PointerByReference();
        checkCall(LIB.MXSymbolCopy(symbol, ref));
        return ref.getValue();
    }

    public static String getSymbolDebugString(Pointer symbol) {
        String[] out = new String[1];
        checkCall(LIB.MXSymbolPrint(symbol, out));
        return out[0];
    }

    public static String getSymbolName(Pointer symbol) {
        String[] out = new String[1];
        IntBuffer success = IntBuffer.allocate(1);
        checkCall(LIB.MXSymbolGetName(symbol, out, success));
        if (success.get() == 1) {
            return out[0];
        }
        return null;
    }

    public static String getSymbolAttr(Pointer symbol, String key) {
        String[] out = new String[1];
        IntBuffer success = IntBuffer.allocate(1);
        checkCall(LIB.MXSymbolGetAttr(symbol, key, out, success));
        if (success.get() == 1) {
            return out[0];
        }
        return null;
    }

    public static void setSymbolAttr(Pointer symbol, String key, String value) {
        checkCall(LIB.MXSymbolSetAttr(symbol, key, value));
    }

    public static PairList<String, String> listSymbolAttr(Pointer symbol) {
        IntBuffer size = IntBuffer.allocate(1);
        PointerByReference ref = new PointerByReference();

        checkCall(LIB.MXSymbolListAttr(symbol, size, ref));

        return toPairList(ref, size.get());
    }

    public static PairList<String, String> listSymbolAttrShallow(Pointer symbol) {
        IntBuffer size = IntBuffer.allocate(1);
        PointerByReference ref = new PointerByReference();

        checkCall(LIB.MXSymbolListAttrShallow(symbol, size, ref));

        return toPairList(ref, size.get());
    }
     */

    public static String[] listSymbolNames(Pointer symbol) {
        IntBuffer size = IntBuffer.allocate(1);
        PointerByReference ref = new PointerByReference();

        checkCall(LIB.NNSymbolListInputNames(symbol, 0, size, ref));

        return toStringArray(ref, size.get());
    }

    public static Pointer getSymbolInternals(Pointer symbol) {
        PointerByReference ref = new PointerByReference();
        checkCall(LIB.MXSymbolGetInternals(symbol, ref));
        return ref.getValue();
    }

    /* Need tests
    public static String[] listSymbolArguments(Pointer symbol) {
        IntBuffer size = IntBuffer.allocate(1);
        PointerByReference ref = new PointerByReference();

        checkCall(LIB.MXSymbolListArguments(symbol, size, ref));

        return toStringArray(ref, size.get());
    }

    public static int getSymbolNumOutputs(Pointer symbol) {
        IntBuffer size = IntBuffer.allocate(1);
        checkCall(LIB.MXSymbolGetNumOutputs(symbol, size));
        return size.get();
    }

    public static Pointer getSymbolInternals(Pointer symbol) {
        PointerByReference ref = new PointerByReference();
        checkCall(LIB.MXSymbolGetInternals(symbol, ref));
        return ref.getValue();
    }

    public static String getSymbolChildren(Pointer symbol) {
        PointerByReference ref = new PointerByReference();
        checkCall(LIB.MXSymbolGetChildren(symbol, ref));
        return ref.getValue().getString(0, StandardCharsets.UTF_8.name());
    }

    public static String[] listSymbolAuxiliaryStates(Pointer symbol) {
        IntBuffer size = IntBuffer.allocate(1);
        PointerByReference ref = new PointerByReference();

        checkCall(LIB.MXSymbolListAuxiliaryStates(symbol, size, ref));

        return toStringArray(ref, size.get());
    }

    public static Pointer[] listAtomicSymbolCreators() {
        IntBuffer outSize = IntBuffer.allocate(1);
        PointerByReference ref = new PointerByReference();

        checkCall(LIB.MXSymbolListAtomicSymbolCreators(outSize, ref));

        int size = outSize.get();
        return ref.getValue().getPointerArray(0, size);
    }

    public static String getAtomicSymbolName(Pointer symbol) {
        String[] ret = new String[1];
        checkCall(LIB.MXSymbolGetAtomicSymbolName(symbol, ret));
        return ret[0];
    }

    public static String getInputSymbols(Pointer symbol) {
        IntBuffer size = IntBuffer.allocate(1);
        PointerByReference ref = new PointerByReference();
        checkCall(LIB.MXSymbolGetInputSymbols(symbol, ref, size));
        return ref.getValue().getString(0, StandardCharsets.UTF_8.name());
    }

    public static String cutSubgraph(Pointer symbol) {
        IntBuffer inputSize = IntBuffer.allocate(1);
        PointerByReference ref = new PointerByReference();
        checkCall(LIB.MXSymbolCutSubgraph(symbol, ref, inputSize));
        return ref.getValue().getString(0, StandardCharsets.UTF_8.name());
    }

    public static Pointer createAtomicSymbol(Pointer symbol, String[] keys, String[] values) {
        PointerByReference ref = new PointerByReference();
        checkCall(LIB.MXSymbolCreateAtomicSymbol(symbol, keys.length, keys, values, ref));
        return ref.getValue();
    }

    public static Pointer createVariable(String name) {
        PointerByReference ref = new PointerByReference();
        checkCall(LIB.MXSymbolCreateVariable(name, ref));
        return ref.getValue();
    }

    public static Pointer createGroup(int numOfSymbols, Pointer symbols) {
        PointerByReference symbolsRef = new PointerByReference(symbols);
        PointerByReference ref = new PointerByReference();
        checkCall(LIB.MXSymbolCreateGroup(numOfSymbols, symbolsRef, ref));
        return ref.getValue();
    }
     */

    public static Pointer createSymbolFromFile(String path) {
        PointerByReference ref = new PointerByReference();
        checkCall(LIB.MXSymbolCreateFromFile(path, ref));
        return ref.getValue();
    }

    /* Need tests
    public static Pointer createSymbolFromJson(String json) {
        PointerByReference ref = new PointerByReference();
        checkCall(LIB.MXSymbolCreateFromJSON(json, ref));
        return ref.getValue();
    }

    public static Pointer compose(Pointer symbol, String name, String[] keys) {
        PointerByReference ref = new PointerByReference();

        checkCall(LIB.MXSymbolCompose(symbol, name, keys.length, keys, ref));
        return ref.getValue();
    }

    public static Pointer grad(Pointer symbol, String name, int numWrt, String[] wrt) {
        PointerByReference ref = new PointerByReference();

        checkCall(LIB.MXSymbolCompose(symbol, name, numWrt, wrt, ref));
        return ref.getValue();
    }

    public static Shape[] inferShape(Pointer symbol, String[] keys) {
        IntBuffer argIndex = IntBuffer.allocate(1);
        IntBuffer argShapeData = IntBuffer.allocate(1);
        IntBuffer inShapeSize = IntBuffer.allocate(1);
        PointerByReference inShapeNDim = new PointerByReference();
        PointerByReference inShapeData = new PointerByReference();
        IntBuffer outShapeSize = IntBuffer.allocate(1);
        PointerByReference outShapeNDim = new PointerByReference();
        PointerByReference outShapeData = new PointerByReference();
        IntBuffer auxShapeSize = IntBuffer.allocate(1);
        PointerByReference auxShapeNDim = new PointerByReference();
        PointerByReference auxShapeData = new PointerByReference();
        IntBuffer complete = IntBuffer.allocate(1);

        checkCall(
                LIB.MXSymbolInferShape(
                        symbol,
                        keys.length,
                        keys,
                        argIndex.array(),
                        argShapeData.array(),
                        inShapeSize,
                        inShapeNDim,
                        inShapeData,
                        outShapeSize,
                        outShapeNDim,
                        outShapeData,
                        auxShapeSize,
                        auxShapeNDim,
                        auxShapeData,
                        complete));
        if (complete.get() == 1) {
            Shape[] ret = new Shape[keys.length];
            // TODO: add implementation
            return ret; // NOPMD
        }
        return null;
    }

    public static Pointer inferType(Pointer symbol, String[] keys) {
        int[] argTypeData = new int[1];
        IntBuffer inTypeSize = IntBuffer.allocate(1);
        PointerByReference inTypeData = new PointerByReference();
        IntBuffer outTypeSize = IntBuffer.allocate(1);
        PointerByReference outTypeData = new PointerByReference();
        IntBuffer auxTypeSize = IntBuffer.allocate(1);
        PointerByReference auxTypeData = new PointerByReference();
        IntBuffer complete = IntBuffer.allocate(1);

        checkCall(
                LIB.MXSymbolInferType(
                        symbol,
                        keys.length,
                        keys,
                        argTypeData,
                        inTypeSize,
                        inTypeData,
                        outTypeSize,
                        outTypeData,
                        auxTypeSize,
                        auxTypeData,
                        complete));
        if (complete.get() == 1) {
            return outTypeData.getValue();
        }
        return null;
    }

    public static Pointer quantizeSymbol(
            Pointer symbol,
            String[] excludedSymbols,
            String[] offlineParams,
            String quantizedDType,
            byte calibQuantize) {
        PointerByReference ref = new PointerByReference();
        checkCall(
                LIB.MXQuantizeSymbol(
                        symbol,
                        ref,
                        excludedSymbols.length,
                        excludedSymbols,
                        offlineParams.length,
                        offlineParams,
                        quantizedDType,
                        calibQuantize));
        return ref.getValue();
    }

    public static Pointer setCalibTableToQuantizedSymbol(
            Pointer symbol,
            String[] layerNames,
            FloatBuffer lowQuantiles,
            FloatBuffer highQuantiles) {
        PointerByReference ref = new PointerByReference();
        checkCall(
                LIB.MXSetCalibTableToQuantizedSymbol(
                        symbol, layerNames.length, layerNames, lowQuantiles, highQuantiles, ref));
        return ref.getValue();
    }

    public static Pointer genBackendSubgraph(Pointer symbol, String backend) {
        PointerByReference ref = new PointerByReference();
        checkCall(LIB.MXGenBackendSubgraph(symbol, backend, ref));
        return ref.getValue();
    }
     */

    /////////////////////////////////
    // MXNet Executors
    /////////////////////////////////

    /* Need tests
    public static void freeExecutor(Pointer executor) {
        checkCall(LIB.MXExecutorFree(executor));
    }

    public static String getExecutorDebugString(Pointer executor) {
        String[] ret = new String[1];
        checkCall(LIB.MXExecutorPrint(executor, ret));
        return ret[0];
    }

    public static void forward(Pointer executor, boolean isTrain) {
        checkCall(LIB.MXExecutorForward(executor, isTrain ? 1 : 0));
    }

    public static Pointer backward(Pointer executor, int length) {
        PointerByReference ref = new PointerByReference();
        checkCall(LIB.MXExecutorBackward(executor, length, ref));
        return ref.getValue();
    }

    public static Pointer backwardEx(Pointer executor, int length, boolean isTrain) {
        PointerByReference ref = new PointerByReference();
        checkCall(LIB.MXExecutorBackwardEx(executor, length, ref, isTrain ? 1 : 0));
        return ref.getValue();
    }

    public static NDArray[] getExecutorOutputs(MxNDManager manager, Pointer executor) {
        IntBuffer outSize = IntBuffer.allocate(1);
        PointerByReference ref = new PointerByReference();
        checkCall(LIB.MXExecutorOutputs(executor, outSize, ref));
        int size = outSize.get();
        Pointer[] pointers = ref.getValue().getPointerArray(0, size);
        NDArray[] ndArrays = new NDArray[size];
        for (int i = 0; i < size; ++i) {
            ndArrays[i] = manager.create(pointers[i]);
        }
        return ndArrays;
    }

    public static Pointer bindExecutorSimple(
            Symbol symbol,
            Context context,
            String[] g2cKeys,
            int[] g2cDeviceTypes,
            int[] g2cDeviceIds,
            String[] argParams,
            String[] argParamGradReqs,
            String[] inputArgNames,
            IntBuffer inputShapeData,
            IntBuffer inputShapeIdx,
            String[] inputDataTypeNames,
            int[] inputDataTypes,
            String[] inputStorageTypeNames,
            int[] inputStorageTypes,
            String[] sharedArgParams,
            IntBuffer sharedBufferLen,
            String[] sharedBufferNames,
            PointerByReference sharedBufferHandles,
            PointerByReference updatedSharedBufferNames,
            PointerByReference updatedSharedBufferHandles,
            IntBuffer numInArgs,
            PointerByReference inArgs,
            PointerByReference argGrads,
            IntBuffer numAuxStates,
            PointerByReference auxStates) {
        int deviceId = context.getDeviceId();
        int deviceType = DeviceType.toDeviceType(context);

        PointerByReference ref = new PointerByReference();

        checkCall(
                LIB.MXExecutorSimpleBind(
                        symbol.getHandle(),
                        deviceType,
                        deviceId,
                        g2cKeys == null ? 0 : g2cKeys.length,
                        g2cKeys,
                        g2cDeviceTypes,
                        g2cDeviceIds,
                        argParams.length,
                        argParams,
                        argParamGradReqs,
                        inputArgNames.length,
                        inputArgNames,
                        inputShapeData.array(),
                        inputShapeIdx.array(),
                        inputDataTypeNames.length,
                        inputDataTypeNames,
                        inputDataTypes,
                        inputStorageTypeNames == null ? 0 : inputStorageTypeNames.length,
                        inputStorageTypeNames,
                        inputStorageTypes,
                        sharedArgParams.length,
                        sharedArgParams,
                        sharedBufferLen,
                        sharedBufferNames,
                        sharedBufferHandles,
                        updatedSharedBufferNames,
                        updatedSharedBufferHandles,
                        numInArgs,
                        inArgs,
                        argGrads,
                        numAuxStates,
                        auxStates,
                        null,
                        ref));
        return ref.getValue();
    }

    public static Pointer bindExecutor(
            Pointer executor, Context context, int len, int auxStatesLen) {
        int deviceId = context.getDeviceId();
        int deviceType = DeviceType.toDeviceType(context);
        PointerByReference inArgs = new PointerByReference();
        PointerByReference argGradStore = new PointerByReference();
        IntBuffer gradReqType = IntBuffer.allocate(1);
        PointerByReference auxStates = new PointerByReference();
        PointerByReference ref = new PointerByReference();
        checkCall(
                LIB.MXExecutorBind(
                        executor,
                        deviceType,
                        deviceId,
                        len,
                        inArgs,
                        argGradStore,
                        gradReqType,
                        auxStatesLen,
                        auxStates,
                        ref));
        return ref.getValue();
    }

    public static Pointer bindExecutorX(
            Pointer executor,
            Context context,
            int len,
            int auxStatesLen,
            String[] keys,
            int[] deviceTypes,
            int[] deviceIds) {
        int deviceId = context.getDeviceId();
        int deviceType = DeviceType.toDeviceType(context);
        PointerByReference inArgs = new PointerByReference();
        PointerByReference argGradStore = new PointerByReference();
        IntBuffer gradReqType = IntBuffer.allocate(1);
        PointerByReference auxStates = new PointerByReference();
        PointerByReference ref = new PointerByReference();
        checkCall(
                LIB.MXExecutorBindX(
                        executor,
                        deviceType,
                        deviceId,
                        keys.length,
                        keys,
                        deviceTypes,
                        deviceIds,
                        len,
                        inArgs,
                        argGradStore,
                        gradReqType,
                        auxStatesLen,
                        auxStates,
                        ref));
        return ref.getValue();
    }

    public static Pointer bindExecutorEX(
            Pointer executor,
            Context context,
            int len,
            int auxStatesLen,
            String[] keys,
            int[] deviceTypes,
            int[] deviceIds,
            Pointer sharedExecutor) {
        int deviceId = context.getDeviceId();
        int deviceType = DeviceType.toDeviceType(context);
        PointerByReference inArgs = new PointerByReference();
        PointerByReference argGradStore = new PointerByReference();
        IntBuffer gradReqType = IntBuffer.allocate(1);
        PointerByReference auxStates = new PointerByReference();
        PointerByReference ref = new PointerByReference();
        checkCall(
                LIB.MXExecutorBindEX(
                        executor,
                        deviceType,
                        deviceId,
                        keys.length,
                        keys,
                        deviceTypes,
                        deviceIds,
                        len,
                        inArgs,
                        argGradStore,
                        gradReqType,
                        auxStatesLen,
                        auxStates,
                        sharedExecutor,
                        ref));
        return ref.getValue();
    }

    public static Pointer reshapeExecutor(
            boolean partialShaping,
            boolean allowUpSizing,
            Context context,
            String[] keys,
            int[] deviceTypes,
            int[] deviceIds,
            String[] providedArgShapeNames,
            IntBuffer providedArgShapeData,
            IntBuffer providedArgShapeIdx,
            IntBuffer numInArgs,
            PointerByReference inArgs,
            PointerByReference argGrads,
            IntBuffer numAuxStates,
            PointerByReference auxStates,
            Pointer sharedExecutor) {
        int deviceId = context.getDeviceId();
        int deviceType = DeviceType.toDeviceType(context);
        PointerByReference ref = new PointerByReference();
        checkCall(
                LIB.MXExecutorReshape(
                        partialShaping ? 1 : 0,
                        allowUpSizing ? 1 : 0,
                        deviceType,
                        deviceId,
                        keys.length,
                        keys,
                        deviceTypes,
                        deviceIds,
                        providedArgShapeNames.length,
                        providedArgShapeNames,
                        providedArgShapeData.array(),
                        providedArgShapeIdx.array(),
                        numInArgs,
                        inArgs,
                        argGrads,
                        numAuxStates,
                        auxStates,
                        sharedExecutor,
                        ref));
        return ref.getValue();
    }

    public static Pointer getOptimizedSymbol(Pointer executor) {
        PointerByReference ref = new PointerByReference();
        checkCall(LIB.MXExecutorGetOptimizedSymbol(executor, ref));
        return ref.getValue();
    }

    public static void setMonitorCallback(
            Pointer executor,
            MxnetLibrary.ExecutorMonitorCallback callback,
            Pointer callbackHandle) {
        checkCall(LIB.MXExecutorSetMonitorCallback(executor, callback, callbackHandle));
    }
     */

    /////////////////////////////////
    // MXNet Executors
    /////////////////////////////////

    /*
    public static Pointer[] listDataIters() {
        IntBuffer outSize = IntBuffer.allocate(1);
        PointerByReference ref = new PointerByReference();
        checkCall(LIB.MXListDataIters(outSize, ref));
        return ref.getValue().getPointerArray(0, outSize.get());
    }

    public static Pointer createIter(Pointer iter, String[] keys, String[] values) {
        PointerByReference ref = new PointerByReference();
        checkCall(LIB.MXDataIterCreateIter(iter, keys.length, keys, values, ref));
        return ref.getValue();
    }

    public static String getIterInfo(Pointer iter) {
        String[] name = new String[1];
        String[] description = new String[1];
        IntBuffer numArgs = IntBuffer.allocate(1);
        PointerByReference argNames = new PointerByReference();
        PointerByReference argTypes = new PointerByReference();
        PointerByReference argDesc = new PointerByReference();
        checkCall(
                LIB.MXDataIterGetIterInfo(
                        iter, name, description, numArgs, argNames, argTypes, argDesc));
        return name[0];
    }

    public static void freeDataIter(Pointer iter) {
        checkCall(LIB.MXDataIterFree(iter));
    }

    public static int next(Pointer iter) {
        IntBuffer ret = IntBuffer.allocate(1);
        checkCall(LIB.MXDataIterNext(iter, ret));
        return ret.get();
    }

    public static void beforeFirst(Pointer iter) {
        checkCall(LIB.MXDataIterBeforeFirst(iter));
    }

    public static Pointer getData(Pointer iter) {
        PointerByReference ref = new PointerByReference();
        checkCall(LIB.MXDataIterGetData(iter, ref));
        return ref.getValue();
    }

    public static Pointer getIndex(Pointer iter) {
        LongBuffer outSize = LongBuffer.wrap(new long[1]);
        PointerByReference ref = new PointerByReference();
        checkCall(LIB.MXDataIterGetIndex(iter, ref, outSize));
        return ref.getValue();
    }

    public static int getPadNum(Pointer iter) {
        IntBuffer outSize = IntBuffer.allocate(1);
        checkCall(LIB.MXDataIterGetPadNum(iter, outSize));
        return outSize.get();
    }

    public static String getDataIterLabel(Pointer iter) {
        PointerByReference ref = new PointerByReference();
        checkCall(LIB.MXDataIterGetLabel(iter, ref));
        return ref.getValue().getString(0, StandardCharsets.UTF_8.name());
    }
     */

    /*
    int MXInitPSEnv(int num_vars, String keys[], String vals[]);


    int MXKVStoreCreate(String type, PointerByReference out);


    int MXKVStoreSetGradientCompression(Pointer handle, int num_params, String keys[],
                                        String vals[]);


    int MXKVStoreFree(Pointer handle);


    int MXKVStoreInit(Pointer handle, int num, int keys[], PointerByReference vals);


    int MXKVStoreInitEx(Pointer handle, int num, String keys[], PointerByReference vals);

    int MXKVStorePush(Pointer handle, int num, int keys[], PointerByReference vals, int priority);

    int MXKVStorePushEx(Pointer handle, int num, String keys[], PointerByReference vals,
                        int priority);

    int MXKVStorePullWithSparse(Pointer handle, int num, int keys[], PointerByReference vals,
                                int priority, byte ignore_sparse);

    int MXKVStorePullWithSparseEx(Pointer handle, int num, String keys[], PointerByReference vals,
                                  int priority, byte ignore_sparse);


    int MXKVStorePull(Pointer handle, int num, int keys[], PointerByReference vals, int priority);

    int MXKVStorePullEx(Pointer handle, int num, String keys[], PointerByReference vals,
                        int priority);

    int MXKVStorePullRowSparse(Pointer handle, int num, int keys[], PointerByReference vals,
                               PointerByReference row_ids, int priority);

    int MXKVStorePullRowSparseEx(Pointer handle, int num, String keys[], PointerByReference vals,
                                 PointerByReference row_ids, int priority);


    int MXKVStoreSetUpdater(Pointer handle, MxnetLibrary.MXKVStoreUpdater updater,
                            Pointer updater_handle);


    int MXKVStoreSetUpdaterEx(Pointer handle, MxnetLibrary.MXKVStoreUpdater updater,
                              MxnetLibrary.MXKVStoreStrUpdater str_updater, Pointer updater_handle);

    int MXKVStoreGetType(Pointer handle, String type[]);


    int MXKVStoreGetRank(Pointer handle, IntBuffer ret);

    int MXKVStoreGetGroupSize(Pointer handle, IntBuffer ret);

    int MXKVStoreIsWorkerNode(IntBuffer ret);

    int MXKVStoreIsServerNode(IntBuffer ret);

    int MXKVStoreIsSchedulerNode(IntBuffer ret);


    int MXKVStoreBarrier(Pointer handle);


    int MXKVStoreSetBarrierBeforeExit(Pointer handle, int barrier_before_exit);


    int MXKVStoreRunServer(Pointer handle, MxnetLibrary.MXKVStoreServerController controller,
                           Pointer controller_handle);


    int MXKVStoreSendCommmandToServers(Pointer handle, int cmd_id, String cmd_body);

    int MXKVStoreGetNumDeadNode(Pointer handle, int node_id, IntBuffer number, int timeout_sec);


    int MXRecordIOWriterCreate(String uri, PointerByReference out);


    int MXRecordIOWriterFree(Pointer handle);


    int MXRecordIOWriterWriteRecord(Pointer handle, String buf, NativeSize size);


    int MXRecordIOWriterTell(Pointer handle, NativeSizeByReference pos);


    int MXRecordIOReaderCreate(String uri, PointerByReference out);


    int MXRecordIOReaderFree(Pointer handle);


    int MXRecordIOReaderReadRecord(Pointer handle, String buf[], NativeSizeByReference size);


    int MXRecordIOReaderSeek(Pointer handle, NativeSize pos);


    int MXRecordIOReaderTell(Pointer handle, NativeSizeByReference pos);


    int MXRtcCreate(ByteBuffer name, int num_input, int num_output, PointerByReference input_names,
                    PointerByReference output_names, PointerByReference inputs,
                    PointerByReference outputs, ByteBuffer kernel, PointerByReference out);


    int MXRtcPush(Pointer handle, int num_input, int num_output, PointerByReference inputs,
                  PointerByReference outputs, int gridDimX, int gridDimY, int gridDimZ,
                  int blockDimX, int blockDimY, int blockDimZ);


    int MXRtcFree(Pointer handle);


    int MXCustomOpRegister(String op_type, MxnetLibrary.CustomOpPropCreator creator);


    int MXCustomFunctionRecord(int num_inputs, PointerByReference inputs, int num_outputs,
                               PointerByReference outputs, MXCallbackList callbacks);


    int MXRtcCudaModuleCreate(String source, int num_options, String options[], int num_exports,
                              String exports[], PointerByReference out);


    int MXRtcCudaModuleFree(Pointer handle);


    int MXRtcCudaKernelCreate(Pointer handle, String name, int num_args, IntBuffer is_ndarray,
                              IntBuffer is_const, IntBuffer arg_types, PointerByReference out);


    int MXRtcCudaKernelFree(Pointer handle);


    int MXRtcCudaKernelCall(Pointer handle, int dev_id, PointerByReference args, int grid_dim_x,
                            int grid_dim_y, int grid_dim_z, int block_dim_x, int block_dim_y,
                            int block_dim_z, int shared_mem);


    int MXNDArrayGetSharedMemHandle(Pointer handle, IntBuffer shared_pid, IntBuffer shared_id);


    int MXNDArrayCreateFromSharedMem(int shared_pid, int shared_id, IntBuffer shape, int ndim,
                                     int dtype, PointerByReference out);
    */

    //////////////////////////////////
    // cached Op
    //////////////////////////////////

    /**
     * Method to create the cached op flags.
     *
     * <p>data_indices : [0, 2, 4] Used to label input location, param_indices : [1, 3] Used to
     * label param location
     *
     * @param block {@link SymbolBlock} that loaded in the backend
     * @param manager NDManager to create NDArray
     * @return CachedOp for inference
     */
    public static CachedOp createCachedOp(SymbolBlock block, MxNDManager manager) {
        Symbol symbol = block.getSymbol();
        PairList<String, MxNDArray> parameters = new PairList<>();
        block.getDirectParameters()
                .forEach(param -> parameters.add(param.getName(), (MxNDArray) param.getArray()));

        String[] allNames = symbol.getAllNames();

        // We assume missing parameters are required input, which must be supplied by user.
        int inputSize = allNames.length - parameters.size();
        Map<String, MxNDArray> paramMap = parameters.toMap();

        // prepare the cachedOp element
        PairList<String, Integer> inputs = new PairList<>(inputSize);
        MxNDArray[] inputNDArray = new MxNDArray[allNames.length];

        int[] paramIndices = new int[parameters.size()];

        // Start forming input array and param indices
        int paramLoc = 0;
        for (int i = 0; i < allNames.length; ++i) {
            String paramName = allNames[i];
            MxNDArray array = paramMap.get(paramName);
            if (array != null) {
                paramIndices[paramLoc] = i;
                // TODO: Change the DataType to non-determined
                // parameter NDArray in MxModel is always loaded in CPU context,
                // we have to copy to desired context to execution.
                // TODO: use array.dup(ctx) instead
                inputNDArray[i] = array.asInContext(manager.getContext(), false);
                paramLoc++;
            } else {
                inputs.add(paramName, i);
            }
        }

        // Creating CachedOp
        // static_alloc and static_shape are enabled by default
        String[] keys = {"data_indices", "param_indices", "static_alloc", "static_shape"};
        String[] values = {inputs.values().toString(), Arrays.toString(paramIndices), "1", "1"};

        Pointer symbolHandle = symbol.getHandle();
        PointerByReference ref = new PointerByReference();
        checkCall(LIB.MXCreateCachedOpEx(symbolHandle, keys.length, keys, values, ref));

        return new CachedOp(ref.getValue(), manager, inputNDArray, inputs);
    }

    public static void freeCachedOp(Pointer handle) {
        checkCall(LIB.MXFreeCachedOp(handle));
    }

    public static MxNDArray[] cachedOpInvoke(
            MxNDManager manager, Pointer cachedOpHandle, MxNDArray[] inputs) {
        Pointer[] inputHandles = new Pointer[inputs.length];
        for (int i = 0; i < inputs.length; i++) {
            inputHandles[i] = inputs[i].getHandle();
        }
        PointerArray array = new PointerArray(inputHandles);
        IntBuffer buf = IntBuffer.allocate(1);
        PointerByReference ref = new PointerByReference();
        PointerByReference outSTypeRef = new PointerByReference();
        checkCall(
                LIB.MXInvokeCachedOpEx(
                        cachedOpHandle, inputs.length, array, buf, ref, outSTypeRef));
        int numOutputs = buf.get();
        Pointer[] ptrArray = ref.getValue().getPointerArray(0, numOutputs);
        int[] sTypes = outSTypeRef.getValue().getIntArray(0, numOutputs);
        MxNDArray[] output = new MxNDArray[numOutputs];
        for (int i = 0; i < numOutputs; i++) {
            if (sTypes[i] != 0) {
                output[i] = manager.create(ptrArray[i], SparseFormat.fromValue(sTypes[i]));
            } else {
                output[i] = manager.create(ptrArray[i]);
            }
        }
        return output;
    }

    public static void checkCall(int ret) {
        if (ret != 0) {
            throw new EngineException("MXNet engine call failed: " + getLastError());
        }
    }

    private static String getLastError() {
        return LIB.MXGetLastError();
    }

    private static String[] toStringArray(PointerByReference ref, int size) {
        if (size == 0) {
            return new String[0];
        }

        Pointer[] pointers = ref.getValue().getPointerArray(0, size);

        String[] arr = new String[size];
        for (int i = 0; i < size; ++i) {
            arr[i] = pointers[i].getString(0, StandardCharsets.UTF_8.name());
        }

        return arr;
    }

    /*
    private static PairList<String, String> toPairList(PointerByReference ref, int size) {
        Pointer[] pointers = ref.getValue().getPointerArray(0, size);

        List<String> names = new ArrayList<>(size);
        List<String> values = new ArrayList<>(size);
        for (Pointer pointer : pointers) {
            String[] pair = pointer.getStringArray(0, 2, StandardCharsets.UTF_8.name());
            names.add(pair[0]);
            values.add(pair[1]);
        }

        return new PairList<>(names, values);
    }
     */

    private static String getOpNamePrefix(String name) {
        for (String prefix : OP_NAME_PREFIX) {
            if (name.startsWith(prefix)) {
                return name.substring(prefix.length());
            }
        }
        return name;
    }
}