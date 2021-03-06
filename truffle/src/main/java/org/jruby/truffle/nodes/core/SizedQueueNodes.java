/*
 * Copyright (c) 2015 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 1.0
 * GNU General Public License version 2
 * GNU Lesser General Public License version 2.1
 */
package org.jruby.truffle.nodes.core;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.CreateCast;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.NodeChildren;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.object.DynamicObject;
import com.oracle.truffle.api.source.SourceSection;

import org.jruby.runtime.Visibility;
import org.jruby.truffle.nodes.RubyNode;
import org.jruby.truffle.nodes.cast.BooleanCastWithDefaultNodeGen;
import org.jruby.truffle.runtime.RubyContext;
import org.jruby.truffle.runtime.control.RaiseException;
import org.jruby.truffle.runtime.layouts.Layouts;
import org.jruby.truffle.runtime.subsystems.ThreadManager.BlockingAction;
import org.jruby.truffle.runtime.util.MethodHandleUtils;
import java.lang.invoke.MethodHandle;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * We do not reuse much of class Queue since we need to be able to replace the queue in this case
 * and methods are small anyway.
 */
@CoreClass(name = "SizedQueue")
public abstract class SizedQueueNodes {

    @CoreMethod(names = "allocate", constructor = true)
    public abstract static class AllocateNode extends CoreMethodArrayArgumentsNode {

        public AllocateNode(RubyContext context, SourceSection sourceSection) {
            super(context, sourceSection);
        }

        @Specialization
        public DynamicObject allocate(DynamicObject rubyClass) {
            return Layouts.SIZED_QUEUE.createSizedQueue(Layouts.CLASS.getInstanceFactory(rubyClass), null);
        }

    }

    @CoreMethod(names = "initialize", visibility = Visibility.PRIVATE, required = 1)
    public abstract static class InitializeNode extends CoreMethodArrayArgumentsNode {

        public InitializeNode(RubyContext context, SourceSection sourceSection) {
            super(context, sourceSection);
        }

        @Specialization
        public DynamicObject initialize(DynamicObject self, int capacity) {
            if (capacity <= 0) {
                CompilerDirectives.transferToInterpreter();
                throw new RaiseException(getContext().getCoreLibrary().argumentError("queue size must be positive", this));
            }

            final BlockingQueue<Object> blockingQueue = new ArrayBlockingQueue<Object>(capacity);
            Layouts.SIZED_QUEUE.setQueue(self, blockingQueue);
            return self;
        }

    }

    @CoreMethod(names = "max=", required = 1)
    public abstract static class SetMaxNode extends CoreMethodArrayArgumentsNode {

        public SetMaxNode(RubyContext context, SourceSection sourceSection) {
            super(context, sourceSection);
        }

        @TruffleBoundary
        @Specialization
        public int setMax(DynamicObject self, int newCapacity) {
            if (newCapacity <= 0) {
                CompilerDirectives.transferToInterpreter();
                throw new RaiseException(getContext().getCoreLibrary().argumentError("queue size must be positive", this));
            }

            final BlockingQueue<Object> oldQueue = Layouts.SIZED_QUEUE.getQueue(self);
            final BlockingQueue<Object> newQueue = new ArrayBlockingQueue<Object>(newCapacity);

            // TODO (eregon, 12 July 2015): racy and what to do if the new capacity is lower?
            Object element;
            while ((element = oldQueue.poll()) != null) {
                newQueue.add(element);
            }
            Layouts.SIZED_QUEUE.setQueue(self, newQueue);

            return newCapacity;
        }

    }

    @CoreMethod(names = "max")
    public abstract static class MaxNode extends CoreMethodArrayArgumentsNode {

        public MaxNode(RubyContext context, SourceSection sourceSection) {
            super(context, sourceSection);
        }

        @TruffleBoundary
        @Specialization
        public int max(DynamicObject self) {
            final BlockingQueue<Object> queue = Layouts.SIZED_QUEUE.getQueue(self);

            // TODO (eregon, 12 July 2015): We could be more accurate here and remember the capacity ourselves
            return queue.size() + queue.remainingCapacity();
        }

    }

    @CoreMethod(names = { "push", "<<", "enq" }, required = 1, optional = 1)
    @NodeChildren({
            @NodeChild(type = RubyNode.class, value = "queue"),
            @NodeChild(type = RubyNode.class, value = "value"),
            @NodeChild(type = RubyNode.class, value = "nonBlocking")
    })
    public abstract static class PushNode extends CoreMethodNode {

        public PushNode(RubyContext context, SourceSection sourceSection) {
            super(context, sourceSection);
        }

        @CreateCast("nonBlocking")
        public RubyNode coerceToBoolean(RubyNode nonBlocking) {
            return BooleanCastWithDefaultNodeGen.create(getContext(), getSourceSection(), false, nonBlocking);
        }

        @Specialization(guards = "!nonBlocking")
        public DynamicObject pushBlocking(DynamicObject self, final Object value, boolean nonBlocking) {
            final BlockingQueue<Object> queue = Layouts.SIZED_QUEUE.getQueue(self);

            getContext().getThreadManager().runUntilResult(this, new BlockingAction<Boolean>() {
                @Override
                public Boolean block() throws InterruptedException {
                    queue.put(value);
                    return SUCCESS;
                }
            });

            return self;
        }

        @TruffleBoundary
        @Specialization(guards = "nonBlocking")
        public DynamicObject pushNonBlock(DynamicObject self, final Object value, boolean nonBlocking) {
            final BlockingQueue<Object> queue = Layouts.SIZED_QUEUE.getQueue(self);

            final boolean pushed = queue.offer(value);
            if (!pushed) {
                CompilerDirectives.transferToInterpreter();
                throw new RaiseException(getContext().getCoreLibrary().threadError("queue full", this));
            }

            return self;
        }

    }

    @CoreMethod(names = { "pop", "shift", "deq" }, optional = 1)
    @NodeChildren({
            @NodeChild(type = RubyNode.class, value = "queue"),
            @NodeChild(type = RubyNode.class, value = "nonBlocking")
    })
    public abstract static class PopNode extends CoreMethodNode {

        public PopNode(RubyContext context, SourceSection sourceSection) {
            super(context, sourceSection);
        }

        @CreateCast("nonBlocking")
        public RubyNode coerceToBoolean(RubyNode nonBlocking) {
            return BooleanCastWithDefaultNodeGen.create(getContext(), getSourceSection(), false, nonBlocking);
        }

        @Specialization(guards = "!nonBlocking")
        public Object popBlocking(DynamicObject self, boolean nonBlocking) {
            final BlockingQueue<Object> queue = Layouts.SIZED_QUEUE.getQueue(self);

            return getContext().getThreadManager().runUntilResult(this, new BlockingAction<Object>() {
                @Override
                public Object block() throws InterruptedException {
                    return queue.take();
                }
            });
        }

        @TruffleBoundary
        @Specialization(guards = "nonBlocking")
        public Object popNonBlock(DynamicObject self, boolean nonBlocking) {
            final BlockingQueue<Object> queue = Layouts.SIZED_QUEUE.getQueue(self);

            final Object value = queue.poll();
            if (value == null) {
                CompilerDirectives.transferToInterpreter();
                throw new RaiseException(getContext().getCoreLibrary().threadError("queue empty", this));
            }

            return value;
        }

    }

    @CoreMethod(names = "empty?")
    public abstract static class EmptyNode extends CoreMethodArrayArgumentsNode {

        public EmptyNode(RubyContext context, SourceSection sourceSection) {
            super(context, sourceSection);
        }

        @TruffleBoundary
        @Specialization
        public boolean empty(DynamicObject self) {
            final BlockingQueue<Object> queue = Layouts.SIZED_QUEUE.getQueue(self);
            return queue.isEmpty();
        }

    }

    @CoreMethod(names = { "size", "length" })
    public abstract static class SizeNode extends CoreMethodArrayArgumentsNode {

        public SizeNode(RubyContext context, SourceSection sourceSection) {
            super(context, sourceSection);
        }

        @Specialization
        public int size(DynamicObject self) {
            final BlockingQueue<Object> queue = Layouts.SIZED_QUEUE.getQueue(self);
            return queue.size();
        }

    }

    @CoreMethod(names = "clear")
    public abstract static class ClearNode extends CoreMethodArrayArgumentsNode {

        public ClearNode(RubyContext context, SourceSection sourceSection) {
            super(context, sourceSection);
        }

        @TruffleBoundary
        @Specialization
        public DynamicObject clear(DynamicObject self) {
            final BlockingQueue<Object> queue = Layouts.SIZED_QUEUE.getQueue(self);
            queue.clear();
            return self;
        }

    }

    @CoreMethod(names = "num_waiting")
    public abstract static class NumWaitingNode extends CoreMethodArrayArgumentsNode {

        private static final MethodHandle LOCK_FIELD_GETTER = MethodHandleUtils.getPrivateGetter(ArrayBlockingQueue.class, "lock");
        private static final MethodHandle NOT_EMPTY_CONDITION_FIELD_GETTER = MethodHandleUtils.getPrivateGetter(ArrayBlockingQueue.class, "notEmpty");
        private static final MethodHandle NOT_FULL_CONDITION_FIELD_GETTER = MethodHandleUtils.getPrivateGetter(ArrayBlockingQueue.class, "notFull");

        public NumWaitingNode(RubyContext context, SourceSection sourceSection) {
            super(context, sourceSection);
        }

        @Specialization
        public int num_waiting(DynamicObject self) {
            final BlockingQueue<Object> queue = Layouts.SIZED_QUEUE.getQueue(self);

            final ArrayBlockingQueue<Object> arrayBlockingQueue = (ArrayBlockingQueue<Object>) queue;

            final ReentrantLock lock;
            final Condition notEmptyCondition;
            final Condition notFullCondition;
            try {
                lock = (ReentrantLock) LOCK_FIELD_GETTER.invokeExact(arrayBlockingQueue);
                notEmptyCondition = (Condition) NOT_EMPTY_CONDITION_FIELD_GETTER.invokeExact(arrayBlockingQueue);
                notFullCondition = (Condition) NOT_FULL_CONDITION_FIELD_GETTER.invokeExact(arrayBlockingQueue);
            } catch (Throwable e) {
                throw new RuntimeException(e);
            }

            getContext().getThreadManager().runUntilResult(this, new BlockingAction<Boolean>() {
                @Override
                public Boolean block() throws InterruptedException {
                    lock.lockInterruptibly();
                    return SUCCESS;
                }
            });
            try {
                return lock.getWaitQueueLength(notEmptyCondition) + lock.getWaitQueueLength(notFullCondition);
            } finally {
                lock.unlock();
            }
        }

    }

}
