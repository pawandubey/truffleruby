/*
 * Copyright (c) 2013, 2020 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 2.0, or
 * GNU General Public License version 2, or
 * GNU Lesser General Public License version 2.1.
 */
package org.truffleruby.core.kernel;

import java.io.File;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.oracle.truffle.api.dsl.CachedContext;
import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.utilities.AssumedValue;
import org.jcodings.specific.UTF8Encoding;
import org.truffleruby.RubyContext;
import org.truffleruby.RubyLanguage;
import org.truffleruby.builtins.CoreMethod;
import org.truffleruby.builtins.CoreMethodArrayArgumentsNode;
import org.truffleruby.builtins.CoreMethodNode;
import org.truffleruby.builtins.CoreModule;
import org.truffleruby.builtins.NonStandard;
import org.truffleruby.builtins.Primitive;
import org.truffleruby.builtins.PrimitiveArrayArgumentsNode;
import org.truffleruby.builtins.UnaryCoreMethodNode;
import org.truffleruby.core.array.ArrayUtils;
import org.truffleruby.core.array.RubyArray;
import org.truffleruby.core.basicobject.BasicObjectNodes;
import org.truffleruby.core.basicobject.BasicObjectNodes.ObjectIDNode;
import org.truffleruby.core.basicobject.BasicObjectNodes.ReferenceEqualNode;
import org.truffleruby.core.binding.BindingNodes;
import org.truffleruby.core.binding.RubyBinding;
import org.truffleruby.core.cast.BooleanCastNode;
import org.truffleruby.core.cast.BooleanCastNodeGen;
import org.truffleruby.core.cast.BooleanCastWithDefaultNodeGen;
import org.truffleruby.core.cast.DurationToMillisecondsNodeGen;
import org.truffleruby.core.cast.NameToJavaStringNode;
import org.truffleruby.core.cast.ToStringOrSymbolNode;
import org.truffleruby.core.cast.ToSymbolNode;
import org.truffleruby.core.encoding.RubyEncoding;
import org.truffleruby.core.exception.GetBacktraceException;
import org.truffleruby.core.format.BytesResult;
import org.truffleruby.core.format.FormatExceptionTranslator;
import org.truffleruby.core.format.exceptions.FormatException;
import org.truffleruby.core.format.exceptions.InvalidFormatException;
import org.truffleruby.core.format.printf.PrintfCompiler;
import org.truffleruby.core.hash.HashOperations;
import org.truffleruby.core.inlined.AlwaysInlinedMethodNode;
import org.truffleruby.core.inlined.InlinedDispatchNode;
import org.truffleruby.core.inlined.InlinedMethodNode;
import org.truffleruby.core.kernel.KernelNodesFactory.CopyNodeFactory;
import org.truffleruby.core.kernel.KernelNodesFactory.DupNodeFactory;
import org.truffleruby.core.kernel.KernelNodesFactory.InitializeCopyNodeFactory;
import org.truffleruby.core.kernel.KernelNodesFactory.SameOrEqualNodeFactory;
import org.truffleruby.core.kernel.KernelNodesFactory.SingletonMethodsNodeFactory;
import org.truffleruby.core.klass.RubyClass;
import org.truffleruby.core.method.MethodFilter;
import org.truffleruby.core.method.RubyMethod;
import org.truffleruby.core.module.RubyModule;
import org.truffleruby.core.numeric.BigIntegerOps;
import org.truffleruby.core.numeric.RubyBignum;
import org.truffleruby.core.proc.ProcNodes.ProcNewNode;
import org.truffleruby.core.proc.ProcOperations;
import org.truffleruby.core.proc.RubyProc;
import org.truffleruby.core.regexp.RubyRegexp;
import org.truffleruby.core.rope.CodeRange;
import org.truffleruby.core.rope.Rope;
import org.truffleruby.core.rope.RopeNodes;
import org.truffleruby.core.rope.RopeOperations;
import org.truffleruby.core.string.RubyString;
import org.truffleruby.core.string.StringCachingGuards;
import org.truffleruby.core.string.StringNodes;
import org.truffleruby.core.string.StringNodes.MakeStringNode;
import org.truffleruby.core.string.StringOperations;
import org.truffleruby.core.support.TypeNodes.CheckFrozenNode;
import org.truffleruby.core.support.TypeNodes.ObjectInstanceVariablesNode;
import org.truffleruby.core.support.TypeNodesFactory.ObjectInstanceVariablesNodeFactory;
import org.truffleruby.core.symbol.RubySymbol;
import org.truffleruby.core.symbol.SymbolTable;
import org.truffleruby.core.thread.GetCurrentRubyThreadNode;
import org.truffleruby.core.thread.RubyThread;
import org.truffleruby.core.thread.ThreadManager.BlockingAction;
import org.truffleruby.interop.ToJavaStringNode;
import org.truffleruby.core.string.ImmutableRubyString;
import org.truffleruby.language.ImmutableRubyObject;
import org.truffleruby.language.Nil;
import org.truffleruby.language.NotProvided;
import org.truffleruby.language.RubyBaseNode;
import org.truffleruby.language.RubyDynamicObject;
import org.truffleruby.language.RubyGuards;
import org.truffleruby.language.RubyNode;
import org.truffleruby.language.RubyRootNode;
import org.truffleruby.language.RubySourceNode;
import org.truffleruby.language.Visibility;
import org.truffleruby.language.WarningNode;
import org.truffleruby.language.arguments.ReadCallerFrameNode;
import org.truffleruby.language.arguments.RubyArguments;
import org.truffleruby.language.backtrace.Backtrace;
import org.truffleruby.language.backtrace.BacktraceFormatter;
import org.truffleruby.language.control.RaiseException;
import org.truffleruby.language.dispatch.DispatchConfiguration;
import org.truffleruby.language.dispatch.DispatchNode;
import org.truffleruby.language.dispatch.DispatchingNode;
import org.truffleruby.language.dispatch.InternalRespondToNode;
import org.truffleruby.language.dispatch.RubyCallNode;
import org.truffleruby.language.eval.CreateEvalSourceNode;
import org.truffleruby.language.globals.ReadGlobalVariableNodeGen;
import org.truffleruby.language.library.RubyLibrary;
import org.truffleruby.language.library.RubyStringLibrary;
import org.truffleruby.language.loader.CodeLoader;
import org.truffleruby.language.loader.RequireNode;
import org.truffleruby.language.loader.RequireNodeGen;
import org.truffleruby.language.locals.FindDeclarationVariableNodes.FindAndReadDeclarationVariableNode;
import org.truffleruby.language.methods.DeclarationContext;
import org.truffleruby.language.methods.GetMethodObjectNode;
import org.truffleruby.language.methods.InternalMethod;
import org.truffleruby.language.methods.SharedMethodInfo;
import org.truffleruby.language.objects.AllocationTracing;
import org.truffleruby.language.objects.CheckIVarNameNode;
import org.truffleruby.language.objects.IsANode;
import org.truffleruby.language.objects.IsImmutableObjectNode;
import org.truffleruby.language.objects.LogicalClassNode;
import org.truffleruby.language.objects.MetaClassNode;
import org.truffleruby.language.objects.ShapeCachingGuards;
import org.truffleruby.language.objects.SingletonClassNode;
import org.truffleruby.language.objects.WriteObjectFieldNode;
import org.truffleruby.language.objects.shared.SharedObjects;
import org.truffleruby.parser.ParserContext;
import org.truffleruby.parser.RubySource;
import org.truffleruby.parser.TranslatorEnvironment;
import org.truffleruby.utils.Utils;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.CreateCast;
import com.oracle.truffle.api.dsl.GenerateNodeFactory;
import com.oracle.truffle.api.dsl.GenerateUncached;
import com.oracle.truffle.api.dsl.ImportStatic;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.ReportPolymorphism;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.FrameSlot;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.library.CachedLibrary;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeUtil;
import com.oracle.truffle.api.object.DynamicObjectLibrary;
import com.oracle.truffle.api.object.Property;
import com.oracle.truffle.api.object.Shape;
import com.oracle.truffle.api.profiles.BranchProfile;
import com.oracle.truffle.api.profiles.ConditionProfile;
import com.oracle.truffle.api.source.SourceSection;

@CoreModule("Kernel")
public abstract class KernelNodes {

    /** Check if operands are the same object or call #==. Known as rb_equal() in MRI. The fact Kernel#=== uses this is
     * pure coincidence. */
    @Primitive(name = "object_same_or_equal")
    public abstract static class SameOrEqualNode extends PrimitiveArrayArgumentsNode {

        @Child private DispatchNode equalNode;
        @Child private BooleanCastNode booleanCastNode;

        private final ConditionProfile sameProfile = ConditionProfile.create();

        public static SameOrEqualNode create() {
            return SameOrEqualNodeFactory.create(null);
        }

        public abstract boolean executeSameOrEqual(Object a, Object b);

        @Specialization
        protected boolean sameOrEqual(Object a, Object b,
                @Cached ReferenceEqualNode referenceEqualNode) {
            if (sameProfile.profile(referenceEqualNode.executeReferenceEqual(a, b))) {
                return true;
            } else {
                return areEqual(a, b);
            }
        }

        private boolean areEqual(Object left, Object right) {
            if (equalNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                equalNode = insert(DispatchNode.create());
            }

            if (booleanCastNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                booleanCastNode = insert(BooleanCastNode.create());
            }

            return booleanCastNode.executeToBoolean(equalNode.call(left, "==", right));
        }

    }

    @CoreMethod(names = "===", required = 1)
    public abstract static class CaseCompareNode extends CoreMethodArrayArgumentsNode {

        @Child private SameOrEqualNode sameOrEqualNode = SameOrEqualNode.create();

        @Specialization
        protected boolean caseCmp(Object a, Object b) {
            return sameOrEqualNode.executeSameOrEqual(a, b);
        }

    }

    /** Check if operands are the same object or call #eql? */
    @GenerateUncached
    public abstract static class SameOrEqlNode extends RubyBaseNode {

        public static SameOrEqlNode create() {
            return KernelNodesFactory.SameOrEqlNodeGen.create();
        }

        public static SameOrEqlNode getUncached() {
            return KernelNodesFactory.SameOrEqlNodeGen.getUncached();
        }

        public abstract boolean execute(Object a, Object b);

        @Specialization(guards = "referenceEqual.executeReferenceEqual(a, b)")
        protected boolean refEqual(Object a, Object b,
                @Cached ReferenceEqualNode referenceEqual) {
            return true;
        }

        @Specialization(replaces = "refEqual")
        protected boolean refEqualOrEql(Object a, Object b,
                @Cached ReferenceEqualNode referenceEqual,
                @Cached DispatchNode eql,
                @Cached BooleanCastNode booleanCast) {
            return referenceEqual.executeReferenceEqual(a, b) || booleanCast.executeToBoolean(eql.call(a, "eql?", b));
        }
    }

    @Primitive(name = "find_file")
    public abstract static class FindFileNode extends PrimitiveArrayArgumentsNode {

        @Specialization(guards = "libFeatureString.isRubyString(featureString)")
        protected Object findFile(Object featureString,
                @Cached BranchProfile notFoundProfile,
                @Cached MakeStringNode makeStringNode,
                @CachedLibrary(limit = "2") RubyStringLibrary libFeatureString) {
            String feature = libFeatureString.getJavaString(featureString);
            return findFileString(feature, notFoundProfile, makeStringNode);
        }

        @Specialization
        protected Object findFileString(String featureString,
                @Cached BranchProfile notFoundProfile,
                @Cached MakeStringNode makeStringNode) {
            final String expandedPath = getContext().getFeatureLoader().findFeature(featureString);
            if (expandedPath == null) {
                notFoundProfile.enter();
                return nil;
            }
            return makeStringNode
                    .executeMake(expandedPath, UTF8Encoding.INSTANCE, CodeRange.CR_UNKNOWN);
        }

    }

    @Primitive(name = "get_caller_path")
    public abstract static class GetCallerPathNode extends PrimitiveArrayArgumentsNode {

        @Specialization(guards = "libFeature.isRubyString(feature)")
        @TruffleBoundary
        protected RubyString getCallerPath(Object feature,
                @CachedLibrary(limit = "2") RubyStringLibrary libFeature,
                @Cached MakeStringNode makeStringNode) {
            final String featureString = libFeature.getJavaString(feature);
            final String featurePath;
            if (new File(featureString).isAbsolute()) {
                featurePath = featureString;
            } else {
                final SourceSection sourceSection = getContext()
                        .getCallStack()
                        .getCallerNode()
                        .getEncapsulatingSourceSection();
                if (!BacktraceFormatter.isAvailable(sourceSection)) {
                    throw new RaiseException(
                            getContext(),
                            coreExceptions().loadError("cannot infer basepath", featureString, this));
                }

                String sourcePath = getLanguage().getSourcePath(sourceSection.getSource());

                sourcePath = getContext().getFeatureLoader().canonicalize(sourcePath);

                featurePath = getContext().getFeatureLoader().dirname(sourcePath) + "/" + featureString;
            }

            // Normalize the path like File.expand_path() (e.g., remove "../"), but do not resolve
            // symlinks. MRI does this for #require_relative always, but not for #require, so we
            // need to do it to be compatible in the case the path does not exist, so the
            // LoadError's #path is the same as MRI's.
            return makeStringNode
                    .executeMake(
                            Paths.get(featurePath).normalize().toString(),
                            UTF8Encoding.INSTANCE,
                            CodeRange.CR_UNKNOWN);
        }

    }

    @Primitive(name = "load_feature")
    public abstract static class LoadFeatureNode extends PrimitiveArrayArgumentsNode {

        @Child private RequireNode requireNode = RequireNodeGen.create();

        @Specialization(guards = "libFeatureString.isRubyString(featureString)")
        protected boolean loadFeature(Object featureString, Object expandedPathString,
                @CachedLibrary(limit = "2") RubyStringLibrary libFeatureString) {
            return requireNode.executeRequire(
                    libFeatureString.getJavaString(featureString),
                    expandedPathString);
        }

    }

    @CoreMethod(names = { "<=>" }, required = 1)
    public abstract static class CompareNode extends CoreMethodArrayArgumentsNode {

        @Child private SameOrEqualNode sameOrEqualNode = SameOrEqualNode.create();

        @Specialization
        protected Object compare(Object self, Object other) {
            if (sameOrEqualNode.executeSameOrEqual(self, other)) {
                return 0;
            } else {
                return nil;
            }
        }

    }

    @CoreMethod(names = "binding", isModuleFunction = true)
    public abstract static class BindingNode extends CoreMethodArrayArgumentsNode {

        @Child ReadCallerFrameNode callerFrameNode = new ReadCallerFrameNode();

        @Specialization
        protected RubyBinding binding(VirtualFrame frame) {
            final MaterializedFrame callerFrame = callerFrameNode.execute(frame);
            final SourceSection sourceSection = getCallerSourceSection();

            return BindingNodes.createBinding(getContext(), getLanguage(), callerFrame, sourceSection);
        }

        @TruffleBoundary
        protected SourceSection getCallerSourceSection() {
            return getContext().getCallStack().getCallerNode().getEncapsulatingSourceSection();
        }

    }

    @CoreMethod(names = { "block_given?", "iterator?" }, isModuleFunction = true)
    public abstract static class BlockGivenNode extends CoreMethodArrayArgumentsNode {

        @Child ReadCallerFrameNode callerFrameNode = new ReadCallerFrameNode();

        @Specialization
        protected boolean blockGiven(VirtualFrame frame,
                @Cached FindAndReadDeclarationVariableNode readNode,
                @Cached ConditionProfile blockProfile) {
            MaterializedFrame callerFrame = callerFrameNode.execute(frame);
            return blockProfile
                    .profile(readNode.execute(callerFrame, TranslatorEnvironment.METHOD_BLOCK_NAME, nil) != nil);
        }
    }

    @CoreMethod(names = "__callee__", isModuleFunction = true)
    public abstract static class CalleeNameNode extends CoreMethodArrayArgumentsNode {

        @Specialization
        protected RubySymbol calleeName() {
            // the "called name" of a method.
            return getSymbol(getContext().getCallStack().getCallingMethod().getName());
        }
    }

    @Primitive(name = "canonicalize_path")
    public abstract static class CanonicalizePathNode extends PrimitiveArrayArgumentsNode {

        @Specialization(guards = "strings.isRubyString(string)")
        @TruffleBoundary
        protected RubyString canonicalPath(Object string,
                @CachedLibrary(limit = "2") RubyStringLibrary strings,
                @Cached StringNodes.MakeStringNode makeStringNode) {
            final String expandedPath = getContext()
                    .getFeatureLoader()
                    .canonicalize(strings.getJavaString(string));
            return makeStringNode.executeMake(expandedPath, UTF8Encoding.INSTANCE, CodeRange.CR_UNKNOWN);
        }

    }

    @Primitive(name = "kernel_caller_locations", lowerFixnum = { 0, 1 })
    public abstract static class CallerLocationsNode extends CoreMethodArrayArgumentsNode {

        @Specialization
        protected Object callerLocations(int omit, NotProvided length) {
            return innerCallerLocations(omit, GetBacktraceException.UNLIMITED);
        }

        @Specialization
        protected Object callerLocations(int omit, int length) {
            return innerCallerLocations(omit, length);
        }

        private Object innerCallerLocations(int omit, int length) {
            // Always skip #caller_locations.
            final int omitted = omit + 1;
            final Backtrace backtrace = getContext().getCallStack().getBacktrace(this, omitted);
            return backtrace.getBacktraceLocations(getContext(), getLanguage(), length, this);
        }
    }

    @CoreMethod(names = "class")
    public abstract static class KernelClassNode extends CoreMethodArrayArgumentsNode {

        @Child private LogicalClassNode classNode = LogicalClassNode.create();

        @Specialization
        protected RubyClass getClass(Object self) {
            return classNode.execute(self);
        }

    }

    @ImportStatic(ShapeCachingGuards.class)
    public abstract static class CopyNode extends UnaryCoreMethodNode {

        public static final Property[] EMPTY_PROPERTY_ARRAY = new Property[0];

        public static CopyNode create() {
            return CopyNodeFactory.create(null);
        }

        @Child private DispatchingNode allocateNode;

        public abstract RubyDynamicObject executeCopy(Object self);

        @ExplodeLoop
        @Specialization(
                guards = { "self.getShape() == cachedShape", "properties.length <= MAX_EXPLODE_SIZE" },
                limit = "getCacheLimit()")
        protected RubyDynamicObject copyCached(RubyDynamicObject self,
                @Cached("self.getShape()") Shape cachedShape,
                @Cached(value = "getCopiedProperties(cachedShape)", dimensions = 1) Property[] properties,
                @Cached("createWriteFieldNodes(properties)") DynamicObjectLibrary[] writeFieldNodes) {
            final RubyDynamicObject newObject = (RubyDynamicObject) allocateNode()
                    .call(self.getLogicalClass(), "__allocate__");

            for (int i = 0; i < properties.length; i++) {
                final Property property = properties[i];
                final Object value = property.get(self, cachedShape);
                writeFieldNodes[i].putWithFlags(newObject, property.getKey(), value, property.getFlags());
            }

            return newObject;
        }

        @Specialization(guards = "updateShape(self)")
        protected Object updateShapeAndCopy(RubyDynamicObject self) {
            return executeCopy(self);
        }

        @Specialization(replaces = { "copyCached", "updateShapeAndCopy" })
        protected RubyDynamicObject copyUncached(RubyDynamicObject self) {
            final RubyClass rubyClass = self.getLogicalClass();
            final RubyDynamicObject newObject = (RubyDynamicObject) allocateNode().call(rubyClass, "__allocate__");
            copyInstanceVariables(self, newObject);
            return newObject;
        }

        @Specialization
        protected RubyDynamicObject copyImmutableString(ImmutableRubyString string,
                @CachedContext(RubyLanguage.class) RubyContext context) {
            return (RubyDynamicObject) allocateNode().call(context.getCoreLibrary().stringClass, "__allocate__");
        }

        @Specialization
        protected RubyRegexp copyRubyRegexp(RubyRegexp regexp,
                @CachedContext(RubyLanguage.class) RubyContext context) {
            return (RubyRegexp) allocateNode().call(context.getCoreLibrary().regexpClass, "__allocate__");
        }

        @Specialization
        protected RubyEncoding copyRubyEncoding(RubyEncoding encoding,
                @CachedContext(RubyLanguage.class) RubyContext context) {
            return (RubyEncoding) allocateNode().call(context.getCoreLibrary().encodingClass, "__allocate__");
        }

        protected Property[] getCopiedProperties(Shape shape) {
            final List<Property> copiedProperties = new ArrayList<>();

            for (Property property : shape.getProperties()) {
                if (property.getKey() instanceof String) {
                    copiedProperties.add(property);
                }
            }

            return copiedProperties.toArray(EMPTY_PROPERTY_ARRAY);
        }

        protected DynamicObjectLibrary[] createWriteFieldNodes(Property[] properties) {
            final DynamicObjectLibrary[] nodes = new DynamicObjectLibrary[properties.length];
            for (int i = 0; i < properties.length; i++) {
                nodes[i] = DynamicObjectLibrary.getFactory().createDispatched(1);
            }
            return nodes;
        }

        @TruffleBoundary
        private void copyInstanceVariables(RubyDynamicObject from, RubyDynamicObject to) {
            // Concurrency: OK if callers create the object and publish it after copy
            // Only copy user-level instance variables, hidden ones are initialized later with #initialize_copy.
            for (Property property : getCopiedProperties(from.getShape())) {
                DynamicObjectLibrary.getUncached().putWithFlags(
                        to,
                        property.getKey(),
                        property.get(from, from.getShape()),
                        property.getFlags());
            }
        }

        protected int getCacheLimit() {
            return getLanguage().options.INSTANCE_VARIABLE_CACHE;
        }

        private DispatchingNode allocateNode() {
            if (allocateNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                allocateNode = insert(
                        new InlinedDispatchNode(getLanguage(), BasicObjectNodes.AllocateNode.create()));
            }
            return allocateNode;
        }
    }

    @CoreMethod(names = "clone", keywordAsOptional = "freeze")
    @NodeChild(value = "self", type = RubyNode.class)
    @NodeChild(value = "freeze", type = RubyNode.class)
    public abstract static class CloneNode extends CoreMethodNode {

        @Child private CopyNode copyNode = CopyNode.create();
        @Child private DispatchNode initializeCloneNode = DispatchNode.create();
        @Child private SingletonClassNode singletonClassNode;

        @CreateCast("freeze")
        protected RubyNode coerceToBoolean(RubyNode freeze) {
            return BooleanCastWithDefaultNodeGen.create(true, freeze);
        }

        @Specialization(limit = "getRubyLibraryCacheLimit()")
        protected RubyDynamicObject clone(RubyDynamicObject self, boolean freeze,
                @Cached ConditionProfile isSingletonProfile,
                @Cached ConditionProfile freezeProfile,
                @Cached ConditionProfile isFrozenProfile,
                @Cached ConditionProfile isRubyClass,
                @CachedLibrary("self") RubyLibrary rubyLibrary,
                @CachedLibrary(limit = "getRubyLibraryCacheLimit()") RubyLibrary rubyLibraryFreeze) {
            final RubyDynamicObject newObject = copyNode.executeCopy(self);

            // Copy the singleton class if any.
            final RubyClass selfMetaClass = self.getMetaClass();
            if (isSingletonProfile.profile(selfMetaClass.isSingleton)) {
                final RubyClass newObjectMetaClass = executeSingletonClass(newObject);
                newObjectMetaClass.fields.initCopy(selfMetaClass);
            }

            initializeCloneNode.call(newObject, "initialize_clone", self);

            if (freezeProfile.profile(freeze) && isFrozenProfile.profile(rubyLibrary.isFrozen(self))) {
                rubyLibraryFreeze.freeze(newObject);
            }

            if (isRubyClass.profile(self instanceof RubyClass)) {
                ((RubyClass) newObject).superclass = ((RubyClass) self).superclass;
            }

            return newObject;
        }

        @Specialization
        protected Object cloneBoolean(boolean self, boolean freeze,
                @Cached ConditionProfile freezeProfile) {
            if (freezeProfile.profile(!freeze)) {
                raiseCantUnfreezeError(self);
            }
            return self;
        }

        @Specialization
        protected Object cloneInteger(int self, boolean freeze,
                @Cached ConditionProfile freezeProfile) {
            if (freezeProfile.profile(!freeze)) {
                raiseCantUnfreezeError(self);
            }
            return self;
        }

        @Specialization
        protected Object cloneLong(long self, boolean freeze,
                @Cached ConditionProfile freezeProfile) {
            if (freezeProfile.profile(!freeze)) {
                raiseCantUnfreezeError(self);
            }
            return self;
        }

        @Specialization
        protected Object cloneFloat(double self, boolean freeze,
                @Cached ConditionProfile freezeProfile) {
            if (freezeProfile.profile(!freeze)) {
                raiseCantUnfreezeError(self);
            }
            return self;
        }

        @Specialization(guards = "!isImmutableRubyString(value)")
        protected Object cloneImmutableObject(ImmutableRubyObject value, boolean freeze,
                @Cached ConditionProfile freezeProfile) {
            if (freezeProfile.profile(!freeze)) {
                raiseCantUnfreezeError(value);
            }
            return value;
        }

        @Specialization
        protected RubyDynamicObject cloneImmutableRubyString(ImmutableRubyString self, boolean freeze,
                @Cached ConditionProfile freezeProfile,
                @CachedLibrary(limit = "getRubyLibraryCacheLimit()") RubyLibrary rubyLibraryFreeze,
                @Cached MakeStringNode makeStringNode) {
            final RubyDynamicObject newObject = makeStringNode.fromRope(self.rope);
            if (freezeProfile.profile(freeze)) {
                rubyLibraryFreeze.freeze(newObject);
            }

            return newObject;
        }

        private void raiseCantUnfreezeError(Object self) {
            throw new RaiseException(getContext(), coreExceptions().argumentErrorCantUnfreeze(self, this));
        }

        private RubyClass executeSingletonClass(RubyDynamicObject newObject) {
            if (singletonClassNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                singletonClassNode = insert(SingletonClassNode.create());
            }

            return singletonClassNode.executeSingletonClass(newObject);
        }

    }

    @CoreMethod(names = "dup")
    public abstract static class DupNode extends InlinedMethodNode {

        public static InlinedMethodNode create() {
            return DupNodeFactory.create(null);
        }

        @Child private DispatchingNode initializeDupNode;

        public abstract Object execute(Object self);

        @Specialization
        protected Object dup(Object self,
                @Cached IsImmutableObjectNode isImmutableObjectNode,
                @Cached ConditionProfile immutableProfile,
                @Cached CopyNode copyNode) {
            if (immutableProfile
                    .profile(!(self instanceof ImmutableRubyString) && isImmutableObjectNode.execute(self))) {
                return self;
            }

            final RubyDynamicObject newObject = copyNode.executeCopy(self);

            initializeDupNode().call(newObject, "initialize_dup", self);

            return newObject;
        }

        @Override
        public Object inlineExecute(Frame callerFrame, Object self, Object[] args, Object block) {
            return execute(self);
        }

        @Override
        public InternalMethod getMethod() {
            return getContext().getCoreMethods().KERNEL_DUP;
        }

        protected DispatchingNode initializeDupNode() {
            if (initializeDupNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                initializeDupNode = insert(
                        new InlinedDispatchNode(
                                getLanguage(),
                                InitializeDupCloneNode.create()));
            }
            return initializeDupNode;
        }
    }

    @Primitive(name = "kernel_eval", lowerFixnum = 4)
    @ImportStatic({ StringCachingGuards.class, StringOperations.class })
    @ReportPolymorphism
    public abstract static class EvalNode extends PrimitiveArrayArgumentsNode {

        @Child private CreateEvalSourceNode createEvalSourceNode = new CreateEvalSourceNode();

        public abstract Object execute(VirtualFrame frame, Object target, Object source, RubyBinding binding,
                Object file, int line);

        // If the source defines new local variables, those should be set in the Binding.
        // So we have 2 specializations for whether or not the code defines new local variables.

        @Specialization(
                guards = {
                        "libSource.isRubyString(source)",
                        "libFile.isRubyString(file)",
                        "equalNode.execute(libSource.getRope(source), cachedSource)",
                        "equalNode.execute(libFile.getRope(file), cachedFile)",
                        "line == cachedLine",
                        "!assignsNewUserVariables(getDescriptor(cachedCallTarget))",
                        "bindingDescriptor == getBindingDescriptor(binding)" },
                limit = "getCacheLimit()")
        protected Object evalBindingNoAddsVarsCached(
                Object target, Object source, RubyBinding binding, Object file, int line,
                @CachedLibrary(limit = "2") RubyStringLibrary libSource,
                @CachedLibrary(limit = "2") RubyStringLibrary libFile,
                @Cached("libSource.getRope(source)") Rope cachedSource,
                @Cached("libFile.getRope(file)") Rope cachedFile,
                @Cached("line") int cachedLine,
                @Cached("getBindingDescriptor(binding)") FrameDescriptor bindingDescriptor,
                @Cached("compileSource(cachedSource, getBindingFrame(binding), cachedFile, cachedLine)") RootCallTarget cachedCallTarget,
                @Cached("create(cachedCallTarget)") DirectCallNode callNode,
                @Cached RopeNodes.EqualNode equalNode) {
            final MaterializedFrame parentFrame = binding.getFrame();
            return eval(target, cachedCallTarget, callNode, parentFrame);
        }

        @Specialization(
                guards = {
                        "libSource.isRubyString(source)",
                        "libFile.isRubyString(file)",
                        "equalNode.execute(libSource.getRope(source), cachedSource)",
                        "equalNode.execute(libFile.getRope(file), cachedFile)",
                        "line == cachedLine",
                        "assignsNewUserVariables(getDescriptor(firstCallTarget))",
                        "!assignsNewUserVariables(getDescriptor(cachedCallTarget))",
                        "bindingDescriptor == getBindingDescriptor(binding)" },
                limit = "getCacheLimit()")
        protected Object evalBindingAddsVarsCached(
                Object target, Object source, RubyBinding binding, Object file, int line,
                @CachedLibrary(limit = "2") RubyStringLibrary libSource,
                @CachedLibrary(limit = "2") RubyStringLibrary libFile,
                @Cached("libSource.getRope(source)") Rope cachedSource,
                @Cached("libFile.getRope(file)") Rope cachedFile,
                @Cached("line") int cachedLine,
                @Cached("getBindingDescriptor(binding)") FrameDescriptor bindingDescriptor,
                @Cached("compileSource(cachedSource, getBindingFrame(binding), cachedFile, cachedLine)") RootCallTarget firstCallTarget,
                @Cached("getDescriptor(firstCallTarget).copy()") FrameDescriptor newBindingDescriptor,
                @Cached("compileSource(cachedSource, getBindingFrame(binding), newBindingDescriptor, cachedFile, cachedLine)") RootCallTarget cachedCallTarget,
                @Cached("create(cachedCallTarget)") DirectCallNode callNode,
                @Cached RopeNodes.EqualNode equalNode) {
            final MaterializedFrame parentFrame = BindingNodes.newFrame(binding, newBindingDescriptor);
            return eval(target, cachedCallTarget, callNode, parentFrame);
        }

        @Specialization(guards = { "libSource.isRubyString(source)", "libFile.isRubyString(file)" })
        protected Object evalBindingUncached(Object target, Object source, RubyBinding binding, Object file, int line,
                @Cached IndirectCallNode callNode,
                @CachedLibrary(limit = "2") RubyStringLibrary libFile,
                @CachedLibrary(limit = "2") RubyStringLibrary libSource) {
            final CodeLoader.DeferredCall deferredCall = doEvalX(
                    target,
                    libSource.getRope(source),
                    binding,
                    libFile.getRope(file),
                    line);
            return deferredCall.call(callNode);
        }

        private Object eval(Object target, RootCallTarget callTarget, DirectCallNode callNode,
                MaterializedFrame parentFrame) {
            final SharedMethodInfo sharedMethodInfo = RubyRootNode.of(callTarget).getSharedMethodInfo();
            final InternalMethod method = new InternalMethod(
                    getContext(),
                    sharedMethodInfo,
                    RubyArguments.getMethod(parentFrame).getLexicalScope(),
                    RubyArguments.getDeclarationContext(parentFrame),
                    sharedMethodInfo.getMethodNameForNotBlock(),
                    RubyArguments.getMethod(parentFrame).getDeclaringModule(),
                    Visibility.PUBLIC,
                    callTarget);

            return callNode.call(RubyArguments.pack(
                    parentFrame,
                    null,
                    method,
                    null,
                    target,
                    nil,
                    EMPTY_ARGUMENTS));
        }

        @TruffleBoundary
        private CodeLoader.DeferredCall doEvalX(Object target, Rope source, RubyBinding binding, Rope file, int line) {
            final MaterializedFrame frame = BindingNodes.newFrame(binding.getFrame());
            final DeclarationContext declarationContext = RubyArguments.getDeclarationContext(frame);
            final FrameDescriptor descriptor = frame.getFrameDescriptor();
            RootCallTarget callTarget = parse(source, frame, file, line, false);
            if (assignsNewUserVariables(descriptor)) {
                binding.setFrame(frame);
            }
            return getContext().getCodeLoader().prepareExecute(
                    callTarget,
                    ParserContext.EVAL,
                    declarationContext,
                    frame,
                    target);
        }

        protected RootCallTarget parse(Rope sourceText, MaterializedFrame parentFrame, Rope file, int line,
                boolean ownScopeForAssignments) {
            //intern() to improve footprint
            final String sourceFile = RopeOperations.decodeRope(file).intern();
            final RubySource source = createEvalSourceNode.createEvalSource(sourceText, "eval", sourceFile, line);
            return getContext()
                    .getCodeLoader()
                    .parse(source, ParserContext.EVAL, parentFrame, null, ownScopeForAssignments, this);
        }

        protected RootCallTarget compileSource(Rope sourceText, MaterializedFrame parentFrame, Rope file, int line) {
            return parse(sourceText, parentFrame, file, line, true);
        }

        protected RootCallTarget compileSource(Rope sourceText, MaterializedFrame parentFrame,
                FrameDescriptor additionalVariables, Rope file, int line) {
            return compileSource(sourceText, BindingNodes.newFrame(parentFrame, additionalVariables), file, line);
        }

        protected FrameDescriptor getBindingDescriptor(RubyBinding binding) {
            return BindingNodes.getFrameDescriptor(binding);
        }

        protected FrameDescriptor getDescriptor(RootCallTarget callTarget) {
            return RubyRootNode.of(callTarget).getFrameDescriptor();
        }

        protected MaterializedFrame getBindingFrame(RubyBinding binding) {
            return binding.getFrame();
        }

        protected static boolean assignsNewUserVariables(FrameDescriptor descriptor) {
            for (FrameSlot slot : descriptor.getSlots()) {
                if (!BindingNodes.isHiddenVariable(slot.getIdentifier())) {
                    return true;
                }
            }
            return false;
        }

        protected int getCacheLimit() {
            return getLanguage().options.EVAL_CACHE;
        }
    }

    @CoreMethod(names = "freeze")
    public abstract static class KernelFreezeNode extends CoreMethodArrayArgumentsNode {

        @Specialization(limit = "getRubyLibraryCacheLimit()", guards = "!isRubyDynamicObject(self)")
        protected Object freeze(Object self,
                @CachedLibrary("self") RubyLibrary rubyLibrary) {
            rubyLibrary.freeze(self);
            return self;
        }

        @Specialization(limit = "getRubyLibraryCacheLimit()", guards = "isRubyDynamicObject(self)")
        protected Object freezeDynamicObject(Object self,
                @CachedLibrary("self") RubyLibrary rubyLibrary,
                @CachedLibrary(limit = "1") RubyLibrary rubyLibraryMetaClass,
                @Cached ConditionProfile singletonProfile,
                @Cached MetaClassNode metaClassNode) {
            final RubyClass metaClass = metaClassNode.execute(self);
            if (singletonProfile.profile(metaClass.isSingleton &&
                    !(RubyGuards.isRubyClass(self) && ((RubyClass) self).isSingleton))) {
                if (!rubyLibraryMetaClass.isFrozen(metaClass)) {
                    rubyLibraryMetaClass.freeze(metaClass);
                }
            }
            rubyLibrary.freeze(self);
            return self;
        }

    }

    @ReportPolymorphism
    @CoreMethod(names = "frozen?")
    public abstract static class KernelFrozenNode extends CoreMethodArrayArgumentsNode {

        @Specialization(limit = "getRubyLibraryCacheLimit()")
        protected boolean isFrozen(Object self,
                @CachedLibrary("self") RubyLibrary rubyLibrary) {
            return rubyLibrary.isFrozen(self);
        }

    }

    @CoreMethod(names = "hash")
    public abstract static class HashNode extends CoreMethodArrayArgumentsNode {


        public static HashNode create() {
            return KernelNodesFactory.HashNodeFactory.create(null);
        }

        public abstract Object execute(Object value);

        @Specialization
        protected long hash(int value) {
            return HashOperations.hashLong(value, getContext(), this);
        }

        @Specialization
        protected long hash(long value) {
            return HashOperations.hashLong(value, getContext(), this);
        }

        @Specialization
        protected long hash(double value) {
            return HashOperations.hashDouble(value, getContext(), this);
        }

        @Specialization
        protected long hash(boolean value) {
            return HashOperations.hashBoolean(value, getContext(), this);
        }

        @Specialization
        protected long hashBignum(RubyBignum value) {
            return HashOperations.hashBignum(value, getContext(), this);
        }

        @TruffleBoundary
        @Specialization
        protected int hash(Nil self) {
            return System.identityHashCode(self);
        }

        @TruffleBoundary
        @Specialization
        protected int hashEncoding(RubyEncoding self) {
            return System.identityHashCode(self);
        }

        @TruffleBoundary
        @Specialization(guards = "!isRubyBignum(self)")
        protected int hash(RubyDynamicObject self) {
            return System.identityHashCode(self);
        }
    }

    @CoreMethod(names = "initialize_copy", required = 1)
    public abstract static class InitializeCopyNode extends InlinedMethodNode {

        public static InitializeCopyNode create() {
            return InitializeCopyNodeFactory.create(null);
        }

        @Child protected ReferenceEqualNode equalNode = ReferenceEqualNode.create();

        public abstract Object execute(Object self, Object from);

        @Specialization(guards = "equalNode.executeReferenceEqual(self, from)")
        protected Object initializeCopySame(Object self, Object from) {
            return self;
        }

        @Specialization(guards = "!equalNode.executeReferenceEqual(self, from)")
        protected Object initializeCopy(Object self, Object from,
                @Cached CheckFrozenNode checkFrozenNode,
                @Cached LogicalClassNode lhsClassNode,
                @Cached LogicalClassNode rhsClassNode,
                @Cached BranchProfile errorProfile) {
            checkFrozenNode.execute(self);
            if (lhsClassNode.execute(self) != rhsClassNode.execute(from)) {
                errorProfile.enter();
                throw new RaiseException(
                        getContext(),
                        coreExceptions().typeError("initialize_copy should take same class object", this));
            }

            return self;
        }

        @Override
        public Object inlineExecute(Frame callerFrame, Object self, Object[] args, Object block) {
            assert args.length == 1;
            return execute(self, args[0]);
        }

        @Override
        public InternalMethod getMethod() {
            return getContext().getCoreMethods().KERNEL_INITIALIZE_COPY;
        }
    }

    @CoreMethod(names = { "initialize_dup", "initialize_clone" }, required = 1)
    public abstract static class InitializeDupCloneNode extends InlinedMethodNode {

        public static InitializeDupCloneNode create() {
            return KernelNodesFactory.InitializeDupCloneNodeFactory.create(null);
        }

        @Child private DispatchingNode initializeCopyNode;

        @Specialization
        protected Object initializeDup(RubyDynamicObject self, Object from) {
            return initializeCopyNode().call(self, "initialize_copy", from);
        }

        @Override
        public Object inlineExecute(Frame callerFrame, Object self, Object[] args, Object block) {
            return initializeDup((RubyDynamicObject) self, args[0]);
        }

        @Override
        public InternalMethod getMethod() {
            return getContext().getCoreMethods().KERNEL_INITIALIZE_DUP;
        }

        protected DispatchingNode initializeCopyNode() {
            if (initializeCopyNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                initializeCopyNode = insert(
                        new InlinedDispatchNode(getLanguage(), InitializeCopyNode.create()));
            }
            return initializeCopyNode;
        }
    }

    @CoreMethod(names = "instance_of?", required = 1)
    public abstract static class InstanceOfNode extends CoreMethodArrayArgumentsNode {

        @Child private LogicalClassNode classNode = LogicalClassNode.create();

        @Specialization
        protected boolean instanceOf(Object self, RubyModule module) {
            return classNode.execute(self) == module;
        }

    }

    @CoreMethod(names = "instance_variable_defined?", required = 1)
    @NodeChild(value = "object", type = RubyNode.class)
    @NodeChild(value = "name", type = RubyNode.class)
    public abstract static class InstanceVariableDefinedNode extends CoreMethodNode {

        @CreateCast("name")
        protected RubyNode coerceToString(RubyNode name) {
            return NameToJavaStringNode.create(name);
        }

        @Specialization
        protected boolean isInstanceVariableDefinedBoolean(boolean object, String name) {
            return false;
        }

        @Specialization
        protected boolean isInstanceVariableDefinedInt(int object, String name) {
            return false;
        }

        @Specialization
        protected boolean isInstanceVariableDefinedLong(long object, String name) {
            return false;
        }

        @Specialization
        protected boolean isInstanceVariableDefinedDouble(double object, String name) {
            return false;
        }

        @Specialization
        protected boolean immutable(ImmutableRubyObject object, String name) {
            return false;
        }

        @Specialization
        protected boolean isInstanceVariableDefined(RubyDynamicObject object, String name,
                @Cached CheckIVarNameNode checkIVarNameNode,
                @CachedLibrary(limit = "getDynamicObjectCacheLimit()") DynamicObjectLibrary objectLibrary) {
            checkIVarNameNode.execute(object, name);
            return objectLibrary.containsKey(object, name);
        }

    }

    @CoreMethod(names = "instance_variable_get", required = 1)
    @NodeChild(value = "object", type = RubyNode.class)
    @NodeChild(value = "name", type = RubyNode.class)
    public abstract static class InstanceVariableGetNode extends CoreMethodNode {

        @CreateCast("name")
        protected RubyNode coerceName(RubyNode name) {
            return NameToJavaStringNode.create(name);
        }

        @Specialization
        protected Object instanceVariableGetSymbol(RubyDynamicObject object, String name,
                @Cached CheckIVarNameNode checkIVarNameNode,
                @CachedLibrary(limit = "getDynamicObjectCacheLimit()") DynamicObjectLibrary objectLibrary) {
            checkIVarNameNode.execute(object, name);
            return objectLibrary.getOrDefault(object, name, nil);
        }
    }

    @CoreMethod(names = "instance_variable_set", raiseIfFrozenSelf = true, required = 2)
    @NodeChild(value = "object", type = RubyNode.class)
    @NodeChild(value = "name", type = RubyNode.class)
    @NodeChild(value = "value", type = RubyNode.class)
    public abstract static class InstanceVariableSetNode extends CoreMethodNode {

        @CreateCast("name")
        protected RubyNode coerceName(RubyNode name) {
            return NameToJavaStringNode.create(name);
        }

        @Specialization
        protected Object instanceVariableSet(RubyDynamicObject object, String name, Object value,
                @Cached CheckIVarNameNode checkIVarNameNode,
                @Cached WriteObjectFieldNode writeNode) {
            checkIVarNameNode.execute(object, name);
            writeNode.execute(object, name, value);
            return value;
        }
    }

    @CoreMethod(names = "remove_instance_variable", raiseIfFrozenSelf = true, required = 1)
    @NodeChild(value = "object", type = RubyNode.class)
    @NodeChild(value = "name", type = RubyNode.class)
    public abstract static class RemoveInstanceVariableNode extends CoreMethodNode {

        @CreateCast("name")
        protected RubyNode coerceToString(RubyNode name) {
            return NameToJavaStringNode.create(name);
        }

        @TruffleBoundary
        @Specialization
        protected Object removeInstanceVariable(RubyDynamicObject object, String name) {
            final String ivar = SymbolTable.checkInstanceVariableName(getContext(), name, object, this);
            final Object value = DynamicObjectLibrary.getUncached().getOrDefault(object, ivar, nil);

            if (SharedObjects.isShared(object)) {
                synchronized (object) {
                    removeField(object, name);
                }
            } else {
                removeField(object, name);
            }
            return value;
        }

        private void removeField(RubyDynamicObject object, String name) {
            if (!DynamicObjectLibrary.getUncached().removeKey(object, name)) {
                throw new RaiseException(
                        getContext(),
                        coreExceptions().nameErrorInstanceVariableNotDefined(name, object, this));
            }
        }
    }

    @CoreMethod(names = "instance_variables")
    public abstract static class InstanceVariablesNode extends CoreMethodArrayArgumentsNode {

        @Child private ObjectInstanceVariablesNode instanceVariablesNode = ObjectInstanceVariablesNodeFactory
                .create(null);

        @Specialization
        protected RubyArray instanceVariables(Object self) {
            return instanceVariablesNode.executeGetIVars(self);
        }

    }

    @CoreMethod(names = { "is_a?", "kind_of?" }, required = 1)
    public abstract static class KernelIsANode extends CoreMethodArrayArgumentsNode {

        @Specialization
        protected boolean isA(Object self, RubyModule module,
                @Cached IsANode isANode) {
            return isANode.executeIsA(self, module);
        }

        @Specialization(guards = "!isRubyModule(module)")
        protected boolean isATypeError(Object self, Object module) {
            throw new RaiseException(getContext(), coreExceptions().typeError("class or module required", this));
        }

    }

    @CoreMethod(names = "lambda", isModuleFunction = true, needsBlock = true)
    public abstract static class LambdaNode extends CoreMethodArrayArgumentsNode {

        @Specialization
        protected RubyProc lambda(Nil block) {
            throw new RaiseException(getContext(), coreExceptions().argumentErrorProcWithoutBlock(this));
        }

        @Specialization(guards = { "isLiteralBlock(block)", "block.isLambda()" })
        protected RubyProc lambdaFromLambdaBlock(RubyProc block) {
            return block;
        }

        @Specialization(guards = { "isLiteralBlock(block)", "block.isProc()" })
        protected RubyProc lambdaFromProcBlock(RubyProc block) {
            return ProcOperations.createLambdaFromBlock(getContext(), getLanguage(), block);
        }

        @Specialization(guards = "!isLiteralBlock(block)")
        protected RubyProc lambdaFromExistingProc(RubyProc block) {
            // If the argument isn't a literal, its original behaviour (proc or lambda) is preserved.
            return block;
        }

        @TruffleBoundary
        protected boolean isLiteralBlock(RubyProc block) {
            Node callNode = getContext().getCallStack().getCallerNode();
            RubyCallNode rubyCallNode = NodeUtil.findParent(callNode, RubyCallNode.class);
            return rubyCallNode != null && rubyCallNode.hasLiteralBlock();
        }
    }

    @CoreMethod(names = "__method__", isModuleFunction = true)
    public abstract static class MethodNameNode extends CoreMethodArrayArgumentsNode {

        @Specialization
        protected RubySymbol methodName() {
            // the "original/definition name" of the method.
            InternalMethod internalMethod = getContext().getCallStack().getCallingMethod();
            return getSymbol(internalMethod.getSharedMethodInfo().getMethodNameForNotBlock());
        }

    }

    @GenerateUncached
    @CoreMethod(names = "method", required = 1, alwaysInlined = true)
    public abstract static class MethodNode extends AlwaysInlinedMethodNode {

        @Specialization
        protected RubyMethod method(Frame callerFrame, Object self, Object[] args, Object block, RootCallTarget target,
                @Cached ToStringOrSymbolNode toStringOrSymbolNode,
                @Cached GetMethodObjectNode getMethodObjectNode) {
            Object name = toStringOrSymbolNode.execute(args[0]);
            return getMethodObjectNode.execute(callerFrame, self, name, DispatchConfiguration.PRIVATE);
        }

    }

    @CoreMethod(names = "methods", optional = 1)
    @NodeChild(value = "object", type = RubyNode.class)
    @NodeChild(value = "regular", type = RubyNode.class)
    public abstract static class MethodsNode extends CoreMethodNode {

        @CreateCast("regular")
        protected RubyNode coerceToBoolean(RubyNode regular) {
            return BooleanCastWithDefaultNodeGen.create(true, regular);
        }

        @TruffleBoundary
        @Specialization(guards = "regular")
        protected RubyArray methodsRegular(Object self, boolean regular,
                @Cached MetaClassNode metaClassNode) {
            final RubyModule metaClass = metaClassNode.execute(self);

            Object[] objects = metaClass.fields
                    .filterMethodsOnObject(getLanguage(), regular, MethodFilter.PUBLIC_PROTECTED)
                    .toArray();
            return createArray(objects);
        }

        @Specialization(guards = "!regular")
        protected RubyArray methodsSingleton(VirtualFrame frame, Object self, boolean regular,
                @Cached SingletonMethodsNode singletonMethodsNode) {
            return singletonMethodsNode.executeSingletonMethods(frame, self, false);
        }

    }

    @CoreMethod(names = "nil?", needsSelf = false)
    public abstract static class IsNilNode extends CoreMethodArrayArgumentsNode {

        @Specialization
        protected boolean isNil() {
            return false;
        }
    }

    // A basic Kernel#p for debugging core, overridden later in kernel.rb
    @NonStandard
    @CoreMethod(names = "p", isModuleFunction = true, required = 1)
    public abstract static class DebugPrintNode extends CoreMethodArrayArgumentsNode {

        @Child private DispatchNode callInspectNode = DispatchNode.create();

        @Specialization
        protected Object p(VirtualFrame frame, Object value) {
            Object inspected = callInspectNode.call(value, "inspect");
            print(inspected);
            return value;
        }

        @TruffleBoundary
        private void print(Object inspected) {
            getContext().getEnvOutStream().println(inspected.toString());
        }
    }

    @CoreMethod(names = "private_methods", optional = 1)
    @NodeChild(value = "object", type = RubyNode.class)
    @NodeChild(value = "includeAncestors", type = RubyNode.class)
    public abstract static class PrivateMethodsNode extends CoreMethodNode {

        @Child private MetaClassNode metaClassNode = MetaClassNode.create();

        @CreateCast("includeAncestors")
        protected RubyNode coerceToBoolean(RubyNode includeAncestors) {
            return BooleanCastWithDefaultNodeGen.create(true, includeAncestors);
        }

        @TruffleBoundary
        @Specialization
        protected RubyArray privateMethods(Object self, boolean includeAncestors) {
            RubyClass metaClass = metaClassNode.execute(self);

            Object[] objects = metaClass.fields
                    .filterMethodsOnObject(getLanguage(), includeAncestors, MethodFilter.PRIVATE)
                    .toArray();
            return createArray(objects);
        }

    }

    @CoreMethod(names = "proc", isModuleFunction = true, needsBlock = true)
    public abstract static class ProcNode extends CoreMethodArrayArgumentsNode {

        @Specialization
        protected RubyProc proc(VirtualFrame frame, Object maybeBlock,
                @Cached ProcNewNode procNewNode) {
            return procNewNode.executeProcNew(frame, coreLibrary().procClass, ArrayUtils.EMPTY_ARRAY, maybeBlock);
        }

    }

    @CoreMethod(names = "protected_methods", optional = 1)
    @NodeChild(value = "object", type = RubyNode.class)
    @NodeChild(value = "includeAncestors", type = RubyNode.class)
    public abstract static class ProtectedMethodsNode extends CoreMethodNode {

        @Child private MetaClassNode metaClassNode = MetaClassNode.create();

        @CreateCast("includeAncestors")
        protected RubyNode coerceToBoolean(RubyNode includeAncestors) {
            return BooleanCastWithDefaultNodeGen.create(true, includeAncestors);
        }

        @TruffleBoundary
        @Specialization
        protected RubyArray protectedMethods(Object self, boolean includeAncestors) {
            final RubyClass metaClass = metaClassNode.execute(self);

            Object[] objects = metaClass.fields
                    .filterMethodsOnObject(getLanguage(), includeAncestors, MethodFilter.PROTECTED)
                    .toArray();
            return createArray(objects);
        }

    }

    @GenerateUncached
    @CoreMethod(names = "public_method", required = 1, alwaysInlined = true)
    public abstract static class PublicMethodNode extends AlwaysInlinedMethodNode {

        @Specialization
        protected RubyMethod method(Frame callerFrame, Object self, Object[] args, Object block, RootCallTarget target,
                @Cached ToStringOrSymbolNode toStringOrSymbolNode,
                @Cached GetMethodObjectNode getMethodObjectNode) {
            Object name = toStringOrSymbolNode.execute(args[0]);
            return getMethodObjectNode.execute(callerFrame, self, name, DispatchConfiguration.PUBLIC);
        }

    }

    @CoreMethod(names = "public_methods", optional = 1)
    @NodeChild(value = "object", type = RubyNode.class)
    @NodeChild(value = "includeAncestors", type = RubyNode.class)
    public abstract static class PublicMethodsNode extends CoreMethodNode {

        @Child private MetaClassNode metaClassNode = MetaClassNode.create();

        @CreateCast("includeAncestors")
        protected RubyNode coerceToBoolean(RubyNode includeAncestors) {
            return BooleanCastWithDefaultNodeGen.create(true, includeAncestors);
        }

        @TruffleBoundary
        @Specialization
        protected RubyArray publicMethods(Object self, boolean includeAncestors) {
            final RubyModule metaClass = metaClassNode.execute(self);

            Object[] objects = metaClass.fields
                    .filterMethodsOnObject(getLanguage(), includeAncestors, MethodFilter.PUBLIC)
                    .toArray();
            return createArray(objects);
        }

    }

    @GenerateUncached
    @CoreMethod(names = "public_send", needsBlock = true, required = 1, rest = true, alwaysInlined = true)
    public abstract static class PublicSendNode extends AlwaysInlinedMethodNode {

        @Specialization
        protected Object send(Frame callerFrame, Object self, Object[] args, Object block, RootCallTarget target,
                @Cached(parameters = "PUBLIC") DispatchNode dispatchNode,
                @Cached NameToJavaStringNode nameToJavaString) {
            Object name = args[0];
            Object[] callArgs = ArrayUtils.extractRange(args, 1, args.length);
            return dispatchNode.dispatch(callerFrame, self, nameToJavaString.execute(name), block, callArgs);
        }

    }

    @CoreMethod(names = "respond_to?", required = 1, optional = 1)
    @NodeChild(value = "object", type = RubyNode.class)
    @NodeChild(value = "name", type = RubyNode.class)
    @NodeChild(value = "includeProtectedAndPrivate", type = RubyNode.class)
    public abstract static class RespondToNode extends CoreMethodNode {

        @Child private InternalRespondToNode dispatch;
        @Child private InternalRespondToNode dispatchIgnoreVisibility;
        @Child private InternalRespondToNode dispatchRespondToMissing;
        @Child private ReadCallerFrameNode readCallerFrame;
        @Child private DispatchNode respondToMissingNode;
        @Child private BooleanCastNode booleanCastNode;
        private final ConditionProfile ignoreVisibilityProfile = ConditionProfile.create();
        private final ConditionProfile isTrueProfile = ConditionProfile.create();
        private final ConditionProfile respondToMissingProfile = ConditionProfile.create();

        public RespondToNode() {
            dispatch = InternalRespondToNode.create(DispatchConfiguration.PUBLIC);
            dispatchIgnoreVisibility = InternalRespondToNode.create();
            dispatchRespondToMissing = InternalRespondToNode.create();
        }

        /** Callers should pass null for the frame here, unless they want to use refinements and can ensure the direct
         * caller is a Ruby method */
        public abstract boolean executeDoesRespondTo(VirtualFrame frame, Object object, Object name,
                boolean includeProtectedAndPrivate);

        @CreateCast("includeProtectedAndPrivate")
        protected RubyNode coerceToBoolean(RubyNode includeProtectedAndPrivate) {
            return BooleanCastWithDefaultNodeGen.create(false, includeProtectedAndPrivate);
        }

        @Specialization
        protected boolean doesRespondTo(
                VirtualFrame frame, Object object, Object name, boolean includeProtectedAndPrivate,
                @Cached ConditionProfile notSymbolOrStringProfile,
                @Cached ToJavaStringNode toJavaString,
                @Cached ToSymbolNode toSymbolNode) {
            if (notSymbolOrStringProfile.profile(!RubyGuards.isRubySymbolOrString(name))) {
                throw new RaiseException(
                        getContext(),
                        coreExceptions().typeErrorIsNotAOrB(object, "symbol", "string", this));
            }

            final boolean ret;
            useCallerRefinements(frame);

            if (ignoreVisibilityProfile.profile(includeProtectedAndPrivate)) {
                ret = dispatchIgnoreVisibility.execute(frame, object, toJavaString.executeToJavaString(name));
            } else {
                ret = dispatch.execute(frame, object, toJavaString.executeToJavaString(name));
            }

            if (isTrueProfile.profile(ret)) {
                return true;
            } else if (respondToMissingProfile
                    .profile(dispatchRespondToMissing.execute(frame, object, "respond_to_missing?"))) {
                return respondToMissing(object, toSymbolNode.execute(name), includeProtectedAndPrivate);
            } else {
                return false;
            }
        }

        private boolean respondToMissing(Object object, RubySymbol name, boolean includeProtectedAndPrivate) {
            if (respondToMissingNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                respondToMissingNode = insert(DispatchNode.create());
            }

            if (booleanCastNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                booleanCastNode = insert(BooleanCastNode.create());
            }

            return booleanCastNode.executeToBoolean(
                    respondToMissingNode.call(object, "respond_to_missing?", name, includeProtectedAndPrivate));
        }

        private void useCallerRefinements(VirtualFrame frame) {
            if (frame != null) {
                if (readCallerFrame == null) {
                    CompilerDirectives.transferToInterpreterAndInvalidate();
                    readCallerFrame = insert(ReadCallerFrameNode.create());
                }
                DeclarationContext context = RubyArguments.getDeclarationContext(readCallerFrame.execute(frame));
                RubyArguments.setDeclarationContext(frame, context);
            }
        }
    }

    @CoreMethod(names = "respond_to_missing?", required = 2)
    public abstract static class RespondToMissingNode extends CoreMethodArrayArgumentsNode {

        @Specialization
        protected boolean doesRespondToMissing(Object object, Object name, Object unusedIncludeAll) {
            return false;
        }

    }

    @CoreMethod(names = "set_trace_func", isModuleFunction = true, required = 1)
    public abstract static class SetTraceFuncNode extends CoreMethodArrayArgumentsNode {

        @Specialization
        protected Object setTraceFunc(Nil traceFunc) {
            getContext().getTraceManager().setTraceFunc(null);
            return nil;
        }

        @Specialization
        protected RubyProc setTraceFunc(RubyProc traceFunc) {
            getContext().getTraceManager().setTraceFunc(traceFunc);
            return traceFunc;
        }
    }

    @CoreMethod(names = "singleton_class")
    public abstract static class SingletonClassMethodNode extends CoreMethodArrayArgumentsNode {

        @Child private SingletonClassNode singletonClassNode = SingletonClassNode.create();

        public abstract RubyClass executeSingletonClass(Object self);

        @Specialization
        protected RubyClass singletonClass(Object self) {
            return singletonClassNode.executeSingletonClass(self);
        }

    }

    @CoreMethod(names = "singleton_method", required = 1)
    @NodeChild(value = "object", type = RubyNode.class)
    @NodeChild(value = "name", type = RubyNode.class)
    public abstract static class SingletonMethodNode extends CoreMethodNode {

        @Child private MetaClassNode metaClassNode = MetaClassNode.create();

        @CreateCast("name")
        protected RubyNode coerceToString(RubyNode name) {
            return NameToJavaStringNode.create(name);
        }

        @Specialization
        protected RubyMethod singletonMethod(Object self, String name,
                @Cached BranchProfile errorProfile,
                @Cached ConditionProfile singletonProfile,
                @Cached ConditionProfile methodProfile) {
            final RubyClass metaClass = metaClassNode.execute(self);

            if (singletonProfile.profile(metaClass.isSingleton)) {
                final InternalMethod method = metaClass.fields.getMethod(name);
                if (methodProfile.profile(method != null && !method.isUndefined())) {
                    final RubyMethod instance = new RubyMethod(
                            coreLibrary().methodClass,
                            getLanguage().methodShape,
                            self,
                            method);
                    AllocationTracing.trace(instance, this);
                    return instance;
                }
            }

            errorProfile.enter();
            throw new RaiseException(
                    getContext(),
                    coreExceptions().nameErrorUndefinedSingletonMethod(name, self, this));
        }

    }

    @CoreMethod(names = "singleton_methods", optional = 1)
    @NodeChild(value = "object", type = RubyNode.class)
    @NodeChild(value = "includeAncestors", type = RubyNode.class)
    public abstract static class SingletonMethodsNode extends CoreMethodNode {

        public static SingletonMethodsNode create() {
            return SingletonMethodsNodeFactory.create(null, null);
        }

        public abstract RubyArray executeSingletonMethods(VirtualFrame frame, Object self, boolean includeAncestors);

        @CreateCast("includeAncestors")
        protected RubyNode coerceToBoolean(RubyNode includeAncestors) {
            return BooleanCastWithDefaultNodeGen.create(true, includeAncestors);
        }

        @TruffleBoundary
        @Specialization
        protected RubyArray singletonMethods(Object self, boolean includeAncestors,
                @Cached MetaClassNode metaClassNode) {
            final RubyClass metaClass = metaClassNode.execute(self);

            if (!metaClass.isSingleton) {
                return createEmptyArray();
            }

            Object[] objects = metaClass.fields
                    .filterSingletonMethods(getLanguage(), includeAncestors, MethodFilter.PUBLIC_PROTECTED)
                    .toArray();
            return createArray(objects);
        }

    }

    @NodeChild(value = "duration", type = RubyNode.class)
    @CoreMethod(names = "sleep", isModuleFunction = true, optional = 1)
    public abstract static class SleepNode extends CoreMethodNode {

        @CreateCast("duration")
        protected RubyNode coerceDuration(RubyNode duration) {
            return DurationToMillisecondsNodeGen.create(false, duration);
        }

        @Specialization
        protected long sleep(long durationInMillis,
                @Cached GetCurrentRubyThreadNode getCurrentRubyThreadNode,
                @Cached BranchProfile errorProfile) {
            if (durationInMillis < 0) {
                errorProfile.enter();
                throw new RaiseException(
                        getContext(),
                        coreExceptions().argumentError("time interval must be positive", this));
            }

            final RubyThread thread = getCurrentRubyThreadNode.execute();

            // Clear the wakeUp flag, following Ruby semantics:
            // it should only be considered if we are inside the sleep when Thread#{run,wakeup} is called.
            thread.wakeUp.set(false);

            return sleepFor(getContext(), thread, durationInMillis, this);
        }

        @TruffleBoundary
        public static long sleepFor(RubyContext context, RubyThread thread, long durationInMillis,
                Node currentNode) {
            assert durationInMillis >= 0;

            // We want a monotonic clock to measure sleep duration
            final long startInNanos = System.nanoTime();

            context.getThreadManager().runUntilResult(currentNode, () -> {
                final long nowInNanos = System.nanoTime();
                final long sleptInNanos = nowInNanos - startInNanos;
                final long sleptInMillis = TimeUnit.NANOSECONDS.toMillis(sleptInNanos);

                if (sleptInMillis >= durationInMillis || thread.wakeUp.getAndSet(false)) {
                    return BlockingAction.SUCCESS;
                }

                Thread.sleep(durationInMillis - sleptInMillis);
                return BlockingAction.SUCCESS;
            });

            return TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - startInNanos);
        }

    }

    @CoreMethod(names = { "format", "sprintf" }, isModuleFunction = true, rest = true, required = 1)
    @ImportStatic({ StringCachingGuards.class, StringOperations.class })
    @ReportPolymorphism
    public abstract static class SprintfNode extends CoreMethodArrayArgumentsNode {

        @Child private MakeStringNode makeStringNode;
        @Child private BooleanCastNode readDebugGlobalNode = BooleanCastNodeGen
                .create(ReadGlobalVariableNodeGen.create("$DEBUG"));

        private final BranchProfile exceptionProfile = BranchProfile.create();
        private final ConditionProfile resizeProfile = ConditionProfile.create();

        @Specialization(
                guards = {
                        "libFormat.isRubyString(format)",
                        "equalNode.execute(libFormat.getRope(format), cachedFormatRope)",
                        "isDebug(frame) == cachedIsDebug" })
        protected RubyString formatCached(VirtualFrame frame, Object format, Object[] arguments,
                @CachedLibrary(limit = "2") RubyStringLibrary libFormat,
                @Cached("isDebug(frame)") boolean cachedIsDebug,
                @Cached("libFormat.getRope(format)") Rope cachedFormatRope,
                @Cached("cachedFormatRope.byteLength()") int cachedFormatLength,
                @Cached("create(compileFormat(format, arguments, isDebug(frame), libFormat))") DirectCallNode callPackNode,
                @Cached RopeNodes.EqualNode equalNode) {
            final BytesResult result;
            try {
                result = (BytesResult) callPackNode.call(
                        new Object[]{ arguments, arguments.length, null });
            } catch (FormatException e) {
                exceptionProfile.enter();
                throw FormatExceptionTranslator.translate(getContext(), this, e);
            }

            return finishFormat(cachedFormatLength, result);
        }

        @Specialization(
                guards = "libFormat.isRubyString(format)",
                replaces = "formatCached")
        protected RubyString formatUncached(VirtualFrame frame, Object format, Object[] arguments,
                @Cached IndirectCallNode callPackNode,
                @CachedLibrary(limit = "2") RubyStringLibrary libFormat) {
            final BytesResult result;
            final boolean isDebug = readDebugGlobalNode.executeBoolean(frame);
            try {
                result = (BytesResult) callPackNode.call(
                        compileFormat(format, arguments, isDebug, libFormat),
                        new Object[]{ arguments, arguments.length, null });
            } catch (FormatException e) {
                exceptionProfile.enter();
                throw FormatExceptionTranslator.translate(getContext(), this, e);
            }

            return finishFormat(libFormat.getRope(format).byteLength(), result);
        }

        private RubyString finishFormat(int formatLength, BytesResult result) {
            byte[] bytes = result.getOutput();

            if (resizeProfile.profile(bytes.length != result.getOutputLength())) {
                bytes = Arrays.copyOf(bytes, result.getOutputLength());
            }

            if (makeStringNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                makeStringNode = insert(MakeStringNode.create());
            }

            return makeStringNode.executeMake(
                    bytes,
                    result.getEncoding().getEncodingForLength(formatLength),
                    result.getStringCodeRange());
        }

        @TruffleBoundary
        protected RootCallTarget compileFormat(Object format, Object[] arguments, boolean isDebug,
                RubyStringLibrary libFormat) {
            try {
                return new PrintfCompiler(getLanguage(), this)
                        .compile(libFormat.getRope(format), arguments, isDebug);
            } catch (InvalidFormatException e) {
                throw new RaiseException(getContext(), coreExceptions().argumentError(e.getMessage(), this));
            }
        }

        protected boolean isDebug(VirtualFrame frame) {
            return readDebugGlobalNode.executeBoolean(frame);
        }

    }

    @CoreMethod(names = "global_variables", isModuleFunction = true)
    public abstract static class KernelGlobalVariablesNode extends CoreMethodArrayArgumentsNode {

        @TruffleBoundary
        @Specialization
        protected RubyArray globalVariables() {
            final String[] keys = coreLibrary().globalVariables.keys();
            final Object[] store = new Object[keys.length];
            for (int i = 0; i < keys.length; i++) {
                store[i] = getSymbol(keys[i]);
            }
            return createArray(store);
        }

    }

    @CoreMethod(names = "taint")
    public abstract static class KernelTaintNode extends CoreMethodArrayArgumentsNode {

        @Specialization
        protected Object taint(Object object,
                @Cached("new()") WarningNode warningNode) {
            if (warningNode.shouldWarn()) {
                warningNode.warningMessage(
                        getSourceSection(),
                        "Object#taint is deprecated and will be removed in Ruby 3.2.");
            }
            return object;
        }

    }

    @CoreMethod(names = "trust")
    public abstract static class KernelTrustNode extends CoreMethodArrayArgumentsNode {

        @Specialization
        protected Object trust(Object object,
                @Cached("new()") WarningNode warningNode) {
            if (warningNode.shouldWarn()) {
                warningNode.warningMessage(
                        getSourceSection(),
                        "Object#trust is deprecated and will be removed in Ruby 3.2.");
            }
            return object;
        }

    }

    @CoreMethod(names = "tainted?")
    public abstract static class KernelIsTaintedNode extends CoreMethodArrayArgumentsNode {

        @Specialization
        protected boolean isTainted(Object object,
                @Cached("new()") WarningNode warningNode) {
            if (warningNode.shouldWarn()) {
                warningNode.warningMessage(
                        getSourceSection(),
                        "Object#tainted? is deprecated and will be removed in Ruby 3.2.");
            }
            return false;
        }

    }

    @CoreMethod(names = "untrusted?")
    public abstract static class KernelIsUntrustedNode extends CoreMethodArrayArgumentsNode {

        @Specialization
        protected boolean isUntrusted(Object object,
                @Cached("new()") WarningNode warningNode) {
            if (warningNode.shouldWarn()) {
                warningNode.warningMessage(
                        getSourceSection(),
                        "Object#untrusted? is deprecated and will be removed in Ruby 3.2.");
            }
            return false;
        }

    }

    @GenerateUncached
    @GenerateNodeFactory
    @NodeChild(value = "value", type = RubyNode.class)
    @Primitive(name = "kernel_to_hex")
    public abstract static class ToHexStringNode extends RubySourceNode {

        public static ToHexStringNode create() {
            return KernelNodesFactory.ToHexStringNodeFactory.create(null);
        }

        public static ToHexStringNode getUncached() {
            return KernelNodesFactory.ToHexStringNodeFactory.getUncached();
        }

        public abstract String executeToHexString(Object value);

        @Specialization
        protected String toHexString(int value) {
            return toHexString((long) value);
        }

        @TruffleBoundary
        @Specialization
        protected String toHexString(long value) {
            return Long.toHexString(value);
        }

        @Specialization
        protected String toHexString(RubyBignum value) {
            return BigIntegerOps.toString(value.value, 16);
        }

    }

    @GenerateUncached
    @GenerateNodeFactory
    @NodeChild(value = "self", type = RubyNode.class)
    @CoreMethod(names = { "to_s", "inspect" }) // Basic #inspect, refined later in core
    public abstract static class ToSNode extends RubySourceNode {

        public static ToSNode create() {
            return KernelNodesFactory.ToSNodeFactory.create(null);
        }

        public abstract RubyString executeToS(Object self);

        @Specialization
        protected RubyString toS(Object self,
                @Cached LogicalClassNode classNode,
                @Cached MakeStringNode makeStringNode,
                @Cached ObjectIDNode objectIDNode,
                @Cached ToHexStringNode toHexStringNode) {
            String className = classNode.execute(self).fields.getName();
            Object id = objectIDNode.execute(self);
            String hexID = toHexStringNode.executeToHexString(id);

            String javaString = Utils.concat("#<", className, ":0x", hexID, ">");

            return makeStringNode.executeMake(
                    javaString,
                    UTF8Encoding.INSTANCE,
                    CodeRange.CR_UNKNOWN);
        }

        @TruffleBoundary
        public static String uncachedBasicToS(Object self) {
            String className = LogicalClassNode.getUncached().execute(self).fields.getName();
            Object id = ObjectIDNode.getUncached().execute(self);
            String hexID = ToHexStringNode.getUncached().executeToHexString(id);

            return "#<" + className + ":0x" + hexID + ">";
        }

        @TruffleBoundary
        public static String uncachedBasicToS(RubyDynamicObject self) {
            String className = self.getLogicalClass().fields.getName();
            long id = ObjectIDNode.getUncached().execute(self);
            String hexID = Long.toHexString(id);

            return "#<" + className + ":0x" + hexID + ">";
        }

    }

    @CoreMethod(names = "untaint")
    public abstract static class UntaintNode extends CoreMethodArrayArgumentsNode {

        @Specialization
        protected Object untaint(Object object,
                @Cached("new()") WarningNode warningNode) {
            if (warningNode.shouldWarn()) {
                warningNode.warningMessage(
                        getSourceSection(),
                        "Object#untaint is deprecated and will be removed in Ruby 3.2.");
            }
            return object;
        }

    }

    @CoreMethod(names = "untrust")
    public abstract static class UntrustNode extends CoreMethodArrayArgumentsNode {

        @Specialization
        protected Object untrust(Object object,
                @Cached("new()") WarningNode warningNode) {
            if (warningNode.shouldWarn()) {
                warningNode.warningMessage(
                        getSourceSection(),
                        "Object#untrust is deprecated and will be removed in Ruby 3.2.");
            }
            return object;
        }

    }

    @Primitive(name = "warning_get_category")
    public abstract static class WarningGetCategoryNode extends PrimitiveArrayArgumentsNode {

        @Specialization(guards = "category == coreSymbols().DEPRECATED")
        protected boolean getCategoryDeprecated(RubySymbol category) {
            return getContext().getWarningCategoryDeprecated().get();
        }

        @Specialization(guards = "category == coreSymbols().EXPERIMENTAL")
        protected boolean getCategoryExperimental(RubySymbol category) {
            return getContext().getWarningCategoryExperimental().get();
        }

    }

    @Primitive(name = "warning_set_category")
    public abstract static class WarningSetCategoryNode extends PrimitiveArrayArgumentsNode {

        @TruffleBoundary
        @Specialization
        protected boolean setCategory(RubySymbol category, boolean newValue) {
            final AssumedValue<Boolean> existingValue;
            if (category == coreSymbols().DEPRECATED) {
                existingValue = getContext().getWarningCategoryDeprecated();
            } else if (category == coreSymbols().EXPERIMENTAL) {
                existingValue = getContext().getWarningCategoryExperimental();
            } else {
                throw CompilerDirectives.shouldNotReachHere("unexpected warning category");
            }

            if (existingValue.get() != newValue) {
                existingValue.set(newValue);
            }
            return newValue;
        }

    }

}
