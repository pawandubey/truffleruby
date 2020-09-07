/*
 * Copyright (c) 2020 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 2.0, or
 * GNU General Public License version 2, or
 * GNU Lesser General Public License version 2.1.
 */
package org.truffleruby.language.methods;

import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.GenerateUncached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.frame.VirtualFrame;
import org.truffleruby.core.klass.RubyClass;
import org.truffleruby.language.RubyBaseNode;
import org.truffleruby.language.dispatch.DispatchConfiguration;
import org.truffleruby.language.objects.MetaClassNode;

@GenerateUncached
public abstract class LookupMethodOnSelfNode extends RubyBaseNode {

    public static LookupMethodOnSelfNode create() {
        return LookupMethodOnSelfNodeGen.create();
    }

    public InternalMethod lookupProtected(VirtualFrame frame, Object self, String name) {
        return execute(frame, self, name, DispatchConfiguration.PROTECTED);
    }

    public InternalMethod lookupPrivate(
            VirtualFrame frame, Object self, String name, DispatchConfiguration config) {
        return execute(frame, self, name, config);
    }

    public InternalMethod lookupIgnoringVisibility(VirtualFrame frame, Object self, String name) {
        return execute(frame, self, name, DispatchConfiguration.PRIVATE);
    }

    protected abstract InternalMethod execute(Frame frame, Object self, String name, DispatchConfiguration config);

    @Specialization
    protected InternalMethod doLookup(Frame frame, Object self, String name, DispatchConfiguration config,
            @Cached MetaClassNode metaClassNode,
            @Cached LookupMethodNode lookupMethod) {
        final RubyClass metaclass = metaClassNode.execute(self);
        return lookupMethod.execute(frame, metaclass, name, config);
    }
}
