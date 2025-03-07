/*
 * Copyright (c) 2013, 2020 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 2.0, or
 * GNU General Public License version 2, or
 * GNU Lesser General Public License version 2.1.
 */
package org.truffleruby.debug;

import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import com.oracle.truffle.api.TruffleStackTraceElement;
import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.frame.FrameInstance;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.object.DynamicObjectLibrary;
import com.oracle.truffle.api.source.SourceSection;
import org.graalvm.collections.Pair;
import org.jcodings.specific.USASCIIEncoding;
import org.jcodings.specific.UTF8Encoding;
import org.truffleruby.Layouts;
import org.truffleruby.RubyContext;
import org.truffleruby.RubyLanguage;
import org.truffleruby.builtins.CoreMethod;
import org.truffleruby.builtins.CoreMethodArrayArgumentsNode;
import org.truffleruby.builtins.CoreMethodNode;
import org.truffleruby.builtins.CoreModule;
import org.truffleruby.builtins.Primitive;
import org.truffleruby.builtins.PrimitiveArrayArgumentsNode;
import org.truffleruby.builtins.YieldingCoreMethodNode;
import org.truffleruby.core.RubyHandle;
import org.truffleruby.core.array.ArrayGuards;
import org.truffleruby.core.array.ArrayHelpers;
import org.truffleruby.core.array.RubyArray;
import org.truffleruby.core.array.library.ArrayStoreLibrary;
import org.truffleruby.core.binding.BindingNodes;
import org.truffleruby.core.binding.RubyBinding;
import org.truffleruby.core.cast.ToCallTargetNode;
import org.truffleruby.core.hash.RubyHash;
import org.truffleruby.core.method.RubyMethod;
import org.truffleruby.core.method.RubyUnboundMethod;
import org.truffleruby.core.numeric.BigIntegerOps;
import org.truffleruby.core.numeric.RubyBignum;
import org.truffleruby.core.proc.RubyProc;
import org.truffleruby.core.rope.CodeRange;
import org.truffleruby.core.string.RubyString;
import org.truffleruby.core.string.StringNodes.MakeStringNode;
import org.truffleruby.core.thread.ThreadManager;
import org.truffleruby.extra.ffi.Pointer;
import org.truffleruby.interop.BoxedValue;
import org.truffleruby.interop.ToJavaStringNode;
import org.truffleruby.language.ImmutableRubyObject;
import org.truffleruby.core.string.ImmutableRubyString;
import org.truffleruby.language.RubyDynamicObject;
import org.truffleruby.language.RubyRootNode;
import org.truffleruby.language.arguments.RubyArguments;
import org.truffleruby.language.backtrace.BacktraceFormatter;
import org.truffleruby.language.backtrace.InternalRootNode;
import org.truffleruby.language.library.RubyStringLibrary;
import org.truffleruby.language.methods.DeclarationContext;
import org.truffleruby.language.methods.InternalMethod;
import org.truffleruby.language.objects.AllocationTracing;
import org.truffleruby.language.objects.shared.SharedObjects;
import org.truffleruby.language.yield.CallBlockNode;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.CachedContext;
import com.oracle.truffle.api.dsl.ImportStatic;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.instrumentation.EventBinding;
import com.oracle.truffle.api.instrumentation.ExecutionEventNode;
import com.oracle.truffle.api.instrumentation.SourceSectionFilter;
import com.oracle.truffle.api.instrumentation.StandardTags;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.InvalidArrayIndexException;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnknownIdentifierException;
import com.oracle.truffle.api.library.CachedLibrary;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeUtil;
import com.oracle.truffle.api.object.Shape;
import com.oracle.truffle.api.utilities.TriState;

@CoreModule("Truffle::Debug")
public abstract class TruffleDebugNodes {

    @CoreMethod(names = "print", onSingleton = true, required = 1)
    public abstract static class DebugPrintNode extends CoreMethodArrayArgumentsNode {

        @TruffleBoundary
        @Specialization
        protected Object debugPrint(Object string) {
            System.err.println(string.toString());
            return nil;
        }

    }

    @CoreMethod(names = "break_handle", onSingleton = true, required = 2, needsBlock = true, lowerFixnum = 2)
    public abstract static class BreakNode extends CoreMethodArrayArgumentsNode {

        @TruffleBoundary
        @Specialization(guards = "strings.isRubyString(file)")
        protected RubyHandle setBreak(Object file, int line, RubyProc block,
                @CachedLibrary(limit = "2") RubyStringLibrary strings) {
            final String fileString = strings.getJavaString(file);

            final SourceSectionFilter filter = SourceSectionFilter
                    .newBuilder()
                    .mimeTypeIs(RubyLanguage.MIME_TYPES)
                    .sourceIs(source -> source != null && getLanguage().getSourcePath(source).equals(fileString))
                    .lineIs(line)
                    .tagIs(StandardTags.StatementTag.class)
                    .build();

            final EventBinding<?> breakpoint = getContext().getInstrumenter().attachExecutionEventFactory(
                    filter,
                    eventContext -> new ExecutionEventNode() {

                        @Child private CallBlockNode yieldNode = CallBlockNode.create();

                        @Override
                        protected void onEnter(VirtualFrame frame) {
                            yieldNode.yield(
                                    block,
                                    BindingNodes.createBinding(
                                            getContext(),
                                            getLanguage(),
                                            frame.materialize(),
                                            eventContext.getInstrumentedSourceSection()));
                        }

                    });

            final RubyHandle instance = new RubyHandle(
                    coreLibrary().handleClass,
                    getLanguage().handleShape,
                    breakpoint);
            AllocationTracing.trace(instance, this);
            return instance;
        }

    }

    @CoreMethod(names = "remove_handle", onSingleton = true, required = 1)
    public abstract static class RemoveNode extends CoreMethodArrayArgumentsNode {

        @TruffleBoundary
        @Specialization
        protected Object remove(RubyHandle handle) {
            EventBinding.class.cast(handle.object).dispose();
            return nil;
        }

    }

    @CoreMethod(names = "java_class_of", onSingleton = true, required = 1)
    public abstract static class JavaClassOfNode extends CoreMethodArrayArgumentsNode {

        @Child private MakeStringNode makeStringNode = MakeStringNode.create();

        @TruffleBoundary
        @Specialization
        protected RubyString javaClassOf(Object value) {
            return makeStringNode
                    .executeMake(value.getClass().getSimpleName(), UTF8Encoding.INSTANCE, CodeRange.CR_UNKNOWN);
        }

    }

    @CoreMethod(names = "print_backtrace", onSingleton = true)
    public abstract static class PrintBacktraceNode extends CoreMethodNode {

        @TruffleBoundary
        @Specialization
        protected Object printBacktrace() {
            getContext().getDefaultBacktraceFormatter().printBacktraceOnEnvStderr(this);
            return nil;
        }

    }

    @CoreMethod(names = "ast", onSingleton = true, required = 1)
    public abstract static class ASTNode extends CoreMethodArrayArgumentsNode {
        @TruffleBoundary
        @Specialization
        protected Object ast(Object executable,
                @Cached ToCallTargetNode toCallTargetNode) {
            final RootCallTarget callTarget = toCallTargetNode.execute(executable);
            ast(callTarget.getRootNode());
            return nil;
        }

        private Object ast(Node node) {
            if (node == null) {
                return nil;
            }

            final List<Object> array = new ArrayList<>();

            array.add(getSymbol(node.getClass().getSimpleName()));

            for (Node child : node.getChildren()) {
                array.add(ast(child));
            }

            return createArray(array.toArray());
        }
    }

    @CoreMethod(names = "print_ast", onSingleton = true, required = 1)
    public abstract static class PrintASTNode extends CoreMethodArrayArgumentsNode {
        @TruffleBoundary
        @Specialization
        protected Object printAST(Object executable,
                @Cached ToCallTargetNode toCallTargetNode) {
            final RootCallTarget callTarget = toCallTargetNode.execute(executable);
            NodeUtil.printCompactTree(System.err, callTarget.getRootNode());
            return nil;
        }
    }

    @CoreMethod(names = "ast_size", onSingleton = true, required = 1)
    public abstract static class ASTSizeNode extends CoreMethodArrayArgumentsNode {
        @TruffleBoundary
        @Specialization
        protected int astSize(Object executable,
                @Cached ToCallTargetNode toCallTargetNode) {
            final RootCallTarget callTarget = toCallTargetNode.execute(executable);
            return NodeUtil.countNodes(callTarget.getRootNode());
        }
    }

    @CoreMethod(names = "shape", onSingleton = true, required = 1)
    public abstract static class ShapeNode extends CoreMethodArrayArgumentsNode {

        @Child private MakeStringNode makeStringNode = MakeStringNode.create();

        @TruffleBoundary
        @Specialization
        protected RubyString shape(RubyDynamicObject object) {
            return makeStringNode
                    .executeMake(object.getShape().toString(), UTF8Encoding.INSTANCE, CodeRange.CR_UNKNOWN);
        }

    }

    @CoreMethod(names = "array_storage", onSingleton = true, required = 1)
    public abstract static class ArrayStorageNode extends CoreMethodArrayArgumentsNode {

        @Child private MakeStringNode makeStringNode = MakeStringNode.create();

        @TruffleBoundary
        @Specialization
        protected RubyString arrayStorage(RubyArray array) {
            String storage = ArrayStoreLibrary.getFactory().getUncached().toString(array.store);
            return makeStringNode.executeMake(storage, USASCIIEncoding.INSTANCE, CodeRange.CR_UNKNOWN);
        }

    }

    @CoreMethod(names = "array_capacity", onSingleton = true, required = 1)
    @ImportStatic(ArrayGuards.class)
    public abstract static class ArrayCapacityNode extends CoreMethodArrayArgumentsNode {

        @Specialization(limit = "storageStrategyLimit()")
        protected long arrayStorage(RubyArray array,
                @CachedLibrary("array.store") ArrayStoreLibrary stores) {
            return stores.capacity(array.store);
        }

    }

    @CoreMethod(names = "hash_storage", onSingleton = true, required = 1)
    public abstract static class HashStorageNode extends CoreMethodArrayArgumentsNode {

        @Child private MakeStringNode makeStringNode = MakeStringNode.create();

        @TruffleBoundary
        @Specialization
        protected RubyString hashStorage(RubyHash hash) {
            Object store = hash.store;
            String storage = store == null ? "null" : store.getClass().toString();
            return makeStringNode.executeMake(storage, USASCIIEncoding.INSTANCE, CodeRange.CR_7BIT);
        }

    }

    @CoreMethod(names = "shared?", onSingleton = true, required = 1)
    @ImportStatic(SharedObjects.class)
    public abstract static class IsSharedNode extends CoreMethodArrayArgumentsNode {

        @Specialization(
                guards = "object.getShape() == cachedShape",
                assumptions = "cachedShape.getValidAssumption()",
                limit = "getCacheLimit()")
        protected boolean isSharedCached(RubyDynamicObject object,
                @Cached("object.getShape()") Shape cachedShape,
                @Cached("cachedShape.isShared()") boolean shared) {
            return shared;
        }

        @Specialization(replaces = "isSharedCached")
        protected boolean isShared(RubyDynamicObject object) {
            return SharedObjects.isShared(object);
        }

        @Specialization
        protected boolean isSharedImmutable(ImmutableRubyObject object) {
            return true;
        }

        protected int getCacheLimit() {
            return getLanguage().options.INSTANCE_VARIABLE_CACHE;
        }

    }

    @CoreMethod(names = "log_config", onSingleton = true, required = 1)
    public abstract static class LogConfigNode extends CoreMethodArrayArgumentsNode {

        @Specialization
        protected Object logConfig(Object value,
                @Cached ToJavaStringNode toJavaStringNode) {
            config(toJavaStringNode.executeToJavaString(value));
            return nil;
        }

        @TruffleBoundary
        static void config(String message) {
            RubyLanguage.LOGGER.config(message);
        }

    }

    @CoreMethod(names = "log_warning", onSingleton = true, required = 1)
    public abstract static class LogWarningNode extends CoreMethodArrayArgumentsNode {

        @Specialization
        protected Object logWarning(Object value,
                @Cached ToJavaStringNode toJavaStringNode) {
            warning(toJavaStringNode.executeToJavaString(value));
            return nil;
        }

        @TruffleBoundary
        static void warning(String message) {
            RubyLanguage.LOGGER.warning(message);
        }

    }

    @CoreMethod(names = "throw_java_exception", onSingleton = true, required = 1)
    public abstract static class ThrowJavaExceptionNode extends CoreMethodArrayArgumentsNode {

        @TruffleBoundary
        @Specialization(guards = "strings.isRubyString(message)")
        protected Object throwJavaException(Object message,
                @CachedLibrary(limit = "2") RubyStringLibrary strings) {
            callingMethod(strings.getJavaString(message));
            return nil;
        }

        // These two named methods makes it easy to test that the backtrace for a Java exception is what we expect

        private static void callingMethod(String message) {
            throwingMethod(message);
        }

        private static void throwingMethod(String message) {
            throw new RuntimeException(message);
        }

    }

    @CoreMethod(names = "throw_java_exception_with_cause", onSingleton = true, required = 1)
    public abstract static class ThrowJavaExceptionWithCauseNode extends CoreMethodArrayArgumentsNode {

        @TruffleBoundary
        @Specialization(guards = "strings.isRubyString(message)")
        protected Object throwJavaExceptionWithCause(Object message,
                @CachedLibrary(limit = "2") RubyStringLibrary strings) {
            throw new RuntimeException(
                    strings.getJavaString(message),
                    new RuntimeException("cause 1", new RuntimeException("cause 2")));
        }

    }

    @CoreMethod(names = "throw_assertion_error", onSingleton = true, required = 1)
    public abstract static class ThrowAssertionErrorNode extends CoreMethodArrayArgumentsNode {

        @TruffleBoundary
        @Specialization(guards = "strings.isRubyString(message)")
        protected Object throwAssertionError(Object message,
                @CachedLibrary(limit = "2") RubyStringLibrary strings) {
            throw new AssertionError(strings.getJavaString(message));
        }

    }

    @CoreMethod(names = "assert", onSingleton = true, required = 1)
    public abstract static class AssertNode extends CoreMethodArrayArgumentsNode {

        @TruffleBoundary
        @Specialization
        protected Object doAssert(boolean condition) {
            assert condition;
            return nil;
        }

    }

    @CoreMethod(names = "java_class", onSingleton = true)
    public abstract static class JavaClassNode extends CoreMethodArrayArgumentsNode {

        @TruffleBoundary
        @Specialization
        protected Object javaObject() {
            return getContext().getEnv().asGuestValue(BigInteger.class);
        }

    }

    @CoreMethod(names = "java_object", onSingleton = true)
    public abstract static class JavaObjectNode extends CoreMethodArrayArgumentsNode {

        @TruffleBoundary
        @Specialization
        protected Object javaObject() {
            return getContext().getEnv().asGuestValue(new BigInteger("14"));
        }

    }

    @CoreMethod(names = "java_null", onSingleton = true)
    public abstract static class JavaNullNode extends CoreMethodArrayArgumentsNode {

        @TruffleBoundary
        @Specialization
        protected Object javaNull() {
            return getContext().getEnv().asGuestValue(null);
        }

    }

    @CoreMethod(names = "foreign_null", onSingleton = true)
    public abstract static class ForeignNullNode extends CoreMethodArrayArgumentsNode {

        @ExportLibrary(InteropLibrary.class)
        public static class ForeignNull implements TruffleObject {

            @ExportMessage
            protected boolean isNull() {
                return true;
            }

            @ExportMessage
            protected String toDisplayString(boolean allowSideEffects) {
                return "[foreign null]";
            }
        }

        @TruffleBoundary
        @Specialization
        protected Object foreignNull() {
            return new ForeignNull();
        }

    }

    @CoreMethod(names = "foreign_pointer", required = 1, onSingleton = true)
    public abstract static class ForeignPointerNode extends CoreMethodArrayArgumentsNode {

        @ExportLibrary(InteropLibrary.class)
        public static class ForeignPointer implements TruffleObject {

            private final long address;

            public ForeignPointer(long address) {
                this.address = address;
            }

            @ExportMessage
            protected boolean isPointer() {
                return true;
            }

            @ExportMessage
            protected long asPointer() {
                return address;
            }

            @ExportMessage
            protected String toDisplayString(boolean allowSideEffects) {
                return "[foreign pointer]";
            }
        }

        @TruffleBoundary
        @Specialization
        protected Object foreignPointer(long address) {
            return new ForeignPointer(address);
        }

    }

    @CoreMethod(names = "foreign_object", onSingleton = true)
    public abstract static class ForeignObjectNode extends CoreMethodArrayArgumentsNode {

        @ExportLibrary(InteropLibrary.class)
        public static class ForeignObject implements TruffleObject {
            @TruffleBoundary
            @ExportMessage
            protected TriState isIdenticalOrUndefined(Object other) {
                return other instanceof ForeignObject ? TriState.valueOf(this == other) : TriState.UNDEFINED;
            }

            @TruffleBoundary
            @ExportMessage
            protected int identityHashCode() {
                return System.identityHashCode(this);
            }

            @ExportMessage
            protected String toDisplayString(boolean allowSideEffects) {
                return "[foreign object]";
            }
        }

        @TruffleBoundary
        @Specialization
        protected Object foreignObject() {
            return new ForeignObject();
        }

    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    @CoreMethod(names = "foreign_object_from_map", required = 1, onSingleton = true)
    public abstract static class ForeignObjectFromMapNode extends CoreMethodArrayArgumentsNode {

        @ExportLibrary(InteropLibrary.class)
        public static class ForeignObjectFromMap implements TruffleObject {

            private final Map map;

            public ForeignObjectFromMap(Map map) {
                this.map = map;
            }

            @ExportMessage
            protected boolean hasMembers() {
                return true;
            }

            @ExportMessage
            @TruffleBoundary
            protected Object getMembers(boolean includeInternal,
                    @CachedContext(RubyLanguage.class) RubyContext context) {
                return context.getEnv().asGuestValue(map.keySet().toArray());
            }

            @TruffleBoundary
            @ExportMessage
            protected boolean isMemberReadable(String member) {
                return map.containsKey(member);
            }

            @TruffleBoundary
            @ExportMessage
            protected Object readMember(String key) throws UnknownIdentifierException {
                final Object value = map.get(key);
                if (value == null) {
                    throw UnknownIdentifierException.create(key);
                }
                return value;
            }

            @ExportMessage
            protected String toDisplayString(boolean allowSideEffects) {
                return "[foreign object with members]";
            }
        }

        @TruffleBoundary
        @Specialization
        protected Object foreignObjectFromMap(Object map) {
            return new ForeignObjectFromMap((Map) getContext().getEnv().asHostObject(map));
        }

    }

    @CoreMethod(names = "foreign_array_from_java", required = 1, onSingleton = true)
    @ImportStatic(ArrayGuards.class)
    public abstract static class ForeignArrayFromJavaNode extends CoreMethodArrayArgumentsNode {

        @ExportLibrary(InteropLibrary.class)
        public static class ForeignArrayFromJava implements TruffleObject {

            private final Object[] array;

            public ForeignArrayFromJava(Object[] array) {
                this.array = array;
            }

            @ExportMessage
            protected boolean hasArrayElements() {
                return true;
            }

            @ExportMessage(name = "isArrayElementReadable")
            @ExportMessage(name = "isArrayElementModifiable")
            protected boolean isArrayElement(long index) {
                return 0 >= index && index < array.length;
            }

            @TruffleBoundary
            @ExportMessage
            protected Object readArrayElement(long index) throws InvalidArrayIndexException {
                try {
                    return array[(int) index];
                } catch (ArrayIndexOutOfBoundsException e) {
                    throw InvalidArrayIndexException.create(index);
                }
            }

            @TruffleBoundary
            @ExportMessage
            protected void writeArrayElement(long index, Object value) throws InvalidArrayIndexException {
                try {
                    array[(int) index] = value;
                } catch (ArrayIndexOutOfBoundsException e) {
                    throw InvalidArrayIndexException.create(index);
                }
            }

            @ExportMessage
            protected final boolean isArrayElementInsertable(long index) {
                return false;
            }

            @ExportMessage
            protected long getArraySize() {
                return array.length;
            }

            @ExportMessage
            protected String toDisplayString(boolean allowSideEffects) {
                return "[foreign array]";
            }
        }

        @TruffleBoundary
        @Specialization(limit = "storageStrategyLimit()")
        protected Object foreignArrayFromJava(Object array,
                @CachedLibrary("hostObject(array)") ArrayStoreLibrary hostObjects) {
            final Object hostObject = hostObject(array);
            final int size = hostObjects.capacity(hostObject);
            final Object[] boxedArray = hostObjects.boxedCopyOfRange(hostObject, 0, size);
            return new ForeignArrayFromJava(boxedArray);
        }

        protected Object hostObject(Object array) {
            return getContext().getEnv().asHostObject(array);
        }
    }

    @CoreMethod(names = "foreign_pointer_array_from_java", required = 1, onSingleton = true)
    @ImportStatic(ArrayGuards.class)
    public abstract static class ForeignPointerArrayFromJavaNode extends ForeignArrayFromJavaNode {

        @ExportLibrary(InteropLibrary.class)
        public static class ForeignPointerArrayFromJava extends ForeignArrayFromJava {

            public ForeignPointerArrayFromJava(Object[] array) {
                super(array);
            }

            @ExportMessage
            protected boolean isPointer() {
                return true;
            }

            @ExportMessage
            protected long asPointer() {
                return 0; // shouldn't be used
            }

            @Override
            @ExportMessage
            protected String toDisplayString(boolean allowSideEffects) {
                return "[foreign pointer array]";
            }
        }

        @Override
        @TruffleBoundary
        @Specialization
        protected Object foreignArrayFromJava(Object array,
                @CachedLibrary(limit = "storageStrategyLimit()") ArrayStoreLibrary stores) {
            final Object hostObject = getContext().getEnv().asHostObject(array);
            final int size = stores.capacity(hostObject);
            return new ForeignPointerArrayFromJava(stores.boxedCopyOfRange(hostObject, 0, size));
        }
    }

    @CoreMethod(names = "foreign_executable", required = 1, onSingleton = true)
    public abstract static class ForeignExecutableNode extends CoreMethodArrayArgumentsNode {

        @ExportLibrary(InteropLibrary.class)
        public static class ForeignExecutable implements TruffleObject {

            private final Object value;

            public ForeignExecutable(Object value) {
                this.value = value;
            }

            @ExportMessage
            protected boolean isExecutable() {
                return true;
            }

            @ExportMessage
            protected Object execute(Object... arguments) {
                return value;
            }

            @ExportMessage
            protected String toDisplayString(boolean allowSideEffects) {
                return "[foreign executable]";
            }
        }

        @TruffleBoundary
        @Specialization
        protected Object foreignExecutable(Object value) {
            return new ForeignExecutable(value);
        }

    }

    @CoreMethod(names = "foreign_string", onSingleton = true, required = 1)
    public abstract static class ForeignStringNode extends CoreMethodArrayArgumentsNode {

        @ExportLibrary(InteropLibrary.class)
        public static class ForeignString implements TruffleObject {

            private final String string;

            public ForeignString(String string) {
                this.string = string;
            }

            @ExportMessage
            protected boolean isString() {
                return true;
            }

            @ExportMessage
            protected String asString() {
                return string;
            }

            @ExportMessage
            protected String toDisplayString(boolean allowSideEffects) {
                return "[foreign string]";
            }
        }

        @TruffleBoundary
        @Specialization(guards = "strings.isRubyString(string)")
        protected Object foreignString(Object string,
                @CachedLibrary(limit = "2") RubyStringLibrary strings) {
            return new ForeignString(strings.getJavaString(string));
        }

    }

    @CoreMethod(names = "foreign_boxed_value", onSingleton = true, required = 1)
    public abstract static class ForeignBoxedNumberNode extends CoreMethodArrayArgumentsNode {

        @TruffleBoundary
        @Specialization
        protected Object foreignBoxedNumber(Object number) {
            return new BoxedValue(number);
        }

    }

    @CoreMethod(names = "float", onSingleton = true, required = 1)
    public abstract static class FloatNode extends CoreMethodArrayArgumentsNode {

        @Specialization
        protected float foreignBoxedNumber(long value) {
            return value;
        }

        @Specialization
        protected float foreignBoxedNumber(RubyBignum value) {
            return (float) BigIntegerOps.doubleValue(value);
        }

        @Specialization
        protected float foreignBoxedNumber(double value) {
            return (float) value;
        }

    }

    @CoreMethod(names = "associated", onSingleton = true, required = 1)
    public abstract static class AssociatedNode extends CoreMethodArrayArgumentsNode {

        @TruffleBoundary
        @Specialization
        protected RubyArray associated(RubyString string) {
            final DynamicObjectLibrary objectLibrary = DynamicObjectLibrary.getUncached();
            Pointer[] associated = (Pointer[]) objectLibrary.getOrDefault(string, Layouts.ASSOCIATED_IDENTIFIER, null);

            if (associated == null) {
                associated = Pointer.EMPTY_ARRAY;
            }

            final long[] associatedValues = new long[associated.length];

            for (int n = 0; n < associated.length; n++) {
                associatedValues[n] = associated[n].getAddress();
            }

            return ArrayHelpers.createArray(getContext(), getLanguage(), associatedValues);
        }

        @TruffleBoundary
        @Specialization
        protected RubyArray associated(ImmutableRubyString string) {
            return ArrayHelpers.createEmptyArray(getContext(), getLanguage());
        }

    }

    @CoreMethod(names = "drain_finalization_queue", onSingleton = true)
    public abstract static class DrainFinalizationQueueNode extends CoreMethodArrayArgumentsNode {

        @TruffleBoundary
        @Specialization
        protected Object drainFinalizationQueue() {
            getContext().getFinalizationService().drainFinalizationQueue();
            return nil;
        }

    }

    @Primitive(name = "frame_declaration_context_to_string")
    public abstract static class FrameDeclarationContextToStringNode extends PrimitiveArrayArgumentsNode {

        @Child private MakeStringNode makeStringNode = MakeStringNode.create();

        @Specialization
        protected RubyString getDeclarationContextToString(VirtualFrame frame) {
            final DeclarationContext declarationContext = RubyArguments.getDeclarationContext(frame);
            return makeStringNode
                    .executeMake(declarationContext.toString(), UTF8Encoding.INSTANCE, CodeRange.CR_UNKNOWN);
        }
    }

    @CoreMethod(names = "get_frame_bindings", onSingleton = true)
    public abstract static class IterateFrameBindingsNode extends YieldingCoreMethodNode {

        /** This logic should be kept in sync with
         * {@link org.truffleruby.language.backtrace.BacktraceFormatter#nextAvailableSourceSection(TruffleStackTraceElement[], int) } */
        @TruffleBoundary
        @Specialization
        protected RubyArray getFrameBindings() {
            Deque<Pair<MaterializedFrame, SourceSection>> stack = new ArrayDeque<>();
            getContext().getCallStack().iterateFrameBindings(5, frameInstance -> {
                final RootNode rootNode = ((RootCallTarget) frameInstance.getCallTarget()).getRootNode();
                if (rootNode instanceof RubyRootNode) {
                    final Frame frame = frameInstance.getFrame(FrameInstance.FrameAccess.MATERIALIZE);
                    final SourceSection sourceSection;
                    if (frameInstance.getCallNode() != null &&
                            BacktraceFormatter
                                    .isAvailable(frameInstance.getCallNode().getEncapsulatingSourceSection())) {
                        sourceSection = frameInstance.getCallNode().getEncapsulatingSourceSection();
                    } else {
                        sourceSection = null;
                    }
                    stack.push(Pair.create(frame.materialize(), sourceSection));
                } else if (!(rootNode instanceof InternalRootNode) &&
                        frameInstance.getCallNode().getEncapsulatingSourceSection() != null) {
                    stack.push(Pair.create(null, null));
                }
                return null;
            });

            while (!stack.isEmpty() && stack.peek().getRight() == null) {
                stack.pop();
            }

            final List<Object> frameBindings = new ArrayList<>();
            SourceSection lastAvailableSourceSection = null;
            while (!stack.isEmpty()) {
                final Pair<MaterializedFrame, SourceSection> frameAndSource = stack.pop();
                final MaterializedFrame frame = frameAndSource.getLeft();
                final SourceSection source = frameAndSource.getRight();
                if (frame != null) {
                    SourceSection sourceSection;
                    if (source != null) {
                        sourceSection = source;
                        lastAvailableSourceSection = source;
                    } else {
                        sourceSection = lastAvailableSourceSection;
                    }
                    final RubyBinding binding = BindingNodes
                            .createBinding(getContext(), getLanguage(), frame, sourceSection);
                    frameBindings.add(binding);
                } else {
                    frameBindings.add(nil);
                }
            }
            Collections.reverse(frameBindings);
            return createArray(frameBindings.toArray());
        }
    }

    @CoreMethod(names = "parse_name_of_method", onSingleton = true, required = 1)
    public abstract static class ParseNameOfMethodNode extends CoreMethodArrayArgumentsNode {

        @Child private MakeStringNode makeStringNode = MakeStringNode.create();

        @Specialization
        protected RubyString parseName(RubyMethod method) {
            return parseName(method.method);
        }

        @Specialization
        protected RubyString parseName(RubyUnboundMethod method) {
            return parseName(method.method);
        }

        protected RubyString parseName(InternalMethod method) {
            String parseName = method.getSharedMethodInfo().getParseName();
            return makeStringNode.executeMake(parseName, UTF8Encoding.INSTANCE, CodeRange.CR_UNKNOWN);
        }

    }

    /** Creates a Truffle thread that is no {@link ThreadManager#isRubyManagedThread(java.lang.Thread)}}. */
    @CoreMethod(names = "create_polyglot_thread", onSingleton = true, required = 1)
    public abstract static class CreatePolyglotThread extends CoreMethodArrayArgumentsNode {
        @Specialization
        protected Object parseName(Object hostRunnable) {
            Runnable runnable = (Runnable) getContext().getEnv().asHostObject(hostRunnable);
            final Thread thread = getContext().getEnv().createThread(runnable);
            return getContext().getEnv().asGuestValue(thread);
        }
    }

}
