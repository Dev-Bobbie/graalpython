/*
 * Copyright (c) 2019, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or
 * data (collectively the "Software"), free of charge and under any and all
 * copyright rights in the Software, and any and all patent rights owned or
 * freely licensable by each licensor hereunder covering either (i) the
 * unmodified Software as contributed to or provided by such licensor, or (ii)
 * the Larger Works (as defined below), to deal in both
 *
 * (a) the Software, and
 *
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 *
 * The above copyright notice and either this complete permission notice or at a
 * minimum a reference to the UPL must be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.oracle.graal.python.builtins.objects.mmap;

import static com.oracle.graal.python.nodes.SpecialMethodNames.__ADD__;
import static com.oracle.graal.python.nodes.SpecialMethodNames.__CONTAINS__;
import static com.oracle.graal.python.nodes.SpecialMethodNames.__ENTER__;
import static com.oracle.graal.python.nodes.SpecialMethodNames.__EQ__;
import static com.oracle.graal.python.nodes.SpecialMethodNames.__EXIT__;
import static com.oracle.graal.python.nodes.SpecialMethodNames.__GETITEM__;
import static com.oracle.graal.python.nodes.SpecialMethodNames.__GE__;
import static com.oracle.graal.python.nodes.SpecialMethodNames.__GT__;
import static com.oracle.graal.python.nodes.SpecialMethodNames.__LEN__;
import static com.oracle.graal.python.nodes.SpecialMethodNames.__LE__;
import static com.oracle.graal.python.nodes.SpecialMethodNames.__LT__;
import static com.oracle.graal.python.nodes.SpecialMethodNames.__MUL__;
import static com.oracle.graal.python.nodes.SpecialMethodNames.__NE__;
import static com.oracle.graal.python.nodes.SpecialMethodNames.__REPR__;
import static com.oracle.graal.python.nodes.SpecialMethodNames.__RMUL__;
import static com.oracle.graal.python.nodes.SpecialMethodNames.__STR__;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channel;
import java.nio.channels.SeekableByteChannel;
import java.util.List;

import com.oracle.graal.python.builtins.Builtin;
import com.oracle.graal.python.builtins.CoreFunctions;
import com.oracle.graal.python.builtins.PythonBuiltinClassType;
import com.oracle.graal.python.builtins.PythonBuiltins;
import com.oracle.graal.python.builtins.objects.PNone;
import com.oracle.graal.python.builtins.objects.bytes.PBytes;
import com.oracle.graal.python.builtins.objects.bytes.PIBytesLike;
import com.oracle.graal.python.builtins.objects.common.SequenceNodes;
import com.oracle.graal.python.builtins.objects.common.SequenceStorageNodes;
import com.oracle.graal.python.builtins.objects.common.SequenceStorageNodes.NoGeneralizationNode;
import com.oracle.graal.python.builtins.objects.common.SequenceStorageNodes.NormalizeIndexNode;
import com.oracle.graal.python.builtins.objects.exception.OSErrorEnum;
import com.oracle.graal.python.builtins.objects.ints.PInt;
import com.oracle.graal.python.builtins.objects.mmap.MMapBuiltinsFactory.InternalLenNodeGen;
import com.oracle.graal.python.builtins.objects.slice.PSlice;
import com.oracle.graal.python.builtins.objects.slice.PSlice.SliceInfo;
import com.oracle.graal.python.nodes.PNodeWithContext;
import com.oracle.graal.python.nodes.SpecialMethodNames;
import com.oracle.graal.python.nodes.call.special.LookupAndCallUnaryNode;
import com.oracle.graal.python.nodes.function.PythonBuiltinBaseNode;
import com.oracle.graal.python.nodes.function.PythonBuiltinNode;
import com.oracle.graal.python.nodes.function.builtins.PythonBinaryBuiltinNode;
import com.oracle.graal.python.nodes.function.builtins.PythonUnaryBuiltinNode;
import com.oracle.graal.python.nodes.truffle.PythonArithmeticTypes;
import com.oracle.graal.python.nodes.util.CastToByteNode;
import com.oracle.graal.python.nodes.util.CastToIndexNode;
import com.oracle.graal.python.nodes.util.CastToJavaLongNode;
import com.oracle.graal.python.nodes.util.ChannelNodes;
import com.oracle.graal.python.nodes.util.ChannelNodes.ReadByteFromChannelNode;
import com.oracle.graal.python.nodes.util.ChannelNodes.ReadFromChannelNode;
import com.oracle.graal.python.nodes.util.ChannelNodes.WriteByteToChannelNode;
import com.oracle.graal.python.nodes.util.ChannelNodes.WriteToChannelNode;
import com.oracle.graal.python.runtime.sequence.storage.ByteSequenceStorage;
import com.oracle.graal.python.runtime.sequence.storage.SequenceStorage;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.GenerateNodeFactory;
import com.oracle.truffle.api.dsl.NodeFactory;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.dsl.TypeSystemReference;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.profiles.BranchProfile;
import com.oracle.truffle.api.profiles.ConditionProfile;

@CoreFunctions(extendClasses = PythonBuiltinClassType.PMMap)
public class MMapBuiltins extends PythonBuiltins {

    protected interface ByteReadingNode {

        static ReadByteFromChannelNode createValueError() {
            return ReadByteFromChannelNode.create(() -> new ChannelNodes.ReadByteErrorHandler() {

                @Override
                public int execute(Channel channel) {
                    throw raise(PythonBuiltinClassType.ValueError, "read byte out of range");
                }
            });
        }

        static ReadByteFromChannelNode createIndexError() {
            return ReadByteFromChannelNode.create(() -> new ChannelNodes.ReadByteErrorHandler() {

                @Override
                public int execute(Channel channel) {
                    throw raise(PythonBuiltinClassType.IndexError, "mmap index out of range");
                }
            });

        }
    }

    protected interface ByteWritingNode {

        static WriteByteToChannelNode createValueError() {
            return WriteByteToChannelNode.create(() -> new ChannelNodes.WriteByteErrorHandler() {

                @Override
                public void execute(Channel channel, byte b) {
                    throw raise(PythonBuiltinClassType.ValueError, "write byte out of range");
                }
            });
        }

        static WriteByteToChannelNode createIndexError() {
            return WriteByteToChannelNode.create(() -> new ChannelNodes.WriteByteErrorHandler() {

                @Override
                public void execute(Channel channel, byte b) {
                    throw raise(PythonBuiltinClassType.IndexError, "mmap index out of range");
                }
            });

        }
    }

    @Override
    protected List<? extends NodeFactory<? extends PythonBuiltinBaseNode>> getNodeFactories() {
        return MMapBuiltinsFactory.getFactories();
    }

    @Builtin(name = __ADD__, fixedNumOfPositionalArgs = 2)
    @GenerateNodeFactory
    abstract static class AddNode extends PythonBinaryBuiltinNode {
    }

    @Builtin(name = __MUL__, fixedNumOfPositionalArgs = 2)
    @GenerateNodeFactory
    abstract static class MulNode extends PythonBuiltinNode {
    }

    @Builtin(name = __RMUL__, fixedNumOfPositionalArgs = 2)
    @GenerateNodeFactory
    abstract static class RMulNode extends MulNode {
    }

    @Builtin(name = __CONTAINS__, fixedNumOfPositionalArgs = 2)
    @GenerateNodeFactory
    abstract static class ContainsNode extends PythonBinaryBuiltinNode {
    }

    @Builtin(name = __LT__, fixedNumOfPositionalArgs = 2)
    @GenerateNodeFactory
    abstract static class LtNode extends PythonBinaryBuiltinNode {
    }

    @Builtin(name = __LE__, fixedNumOfPositionalArgs = 2)
    @GenerateNodeFactory
    abstract static class LeNode extends PythonBinaryBuiltinNode {
    }

    @Builtin(name = __GT__, fixedNumOfPositionalArgs = 2)
    @GenerateNodeFactory
    abstract static class GtNode extends PythonBinaryBuiltinNode {
    }

    @Builtin(name = __GE__, fixedNumOfPositionalArgs = 2)
    @GenerateNodeFactory
    abstract static class GeNode extends PythonBinaryBuiltinNode {
    }

    @Builtin(name = __NE__, fixedNumOfPositionalArgs = 2)
    @GenerateNodeFactory
    abstract static class NeNode extends PythonBinaryBuiltinNode {
    }

    @Builtin(name = __EQ__, fixedNumOfPositionalArgs = 2)
    @GenerateNodeFactory
    abstract static class EqNode extends PythonBinaryBuiltinNode {
    }

    @Builtin(name = __STR__, fixedNumOfPositionalArgs = 1)
    @GenerateNodeFactory
    abstract static class StrNode extends PythonUnaryBuiltinNode {
    }

    @Builtin(name = __REPR__, fixedNumOfPositionalArgs = 1)
    @GenerateNodeFactory
    abstract static class ReprNode extends StrNode {
    }

    @Builtin(name = __GETITEM__, fixedNumOfPositionalArgs = 2)
    @GenerateNodeFactory
    abstract static class GetItemNode extends PythonBuiltinNode implements ByteReadingNode {

        @Specialization(guards = "!isPSlice(idxObj)")
        int doSingle(VirtualFrame frame, PMMap self, Object idxObj,
                        @Cached("createIndexError()") ReadByteFromChannelNode readByteNode,
                        @Cached("createExact()") CastToJavaLongNode castToLongNode,
                        @Cached("create()") InternalLenNode lenNode) {

            try {
                long i = castToLongNode.execute(idxObj);
                long len = lenNode.execute(frame, self);
                SeekableByteChannel channel = self.getChannel();
                long idx = i < 0 ? i + len : i;

                // save current position
                long oldPos = channel.position();

                channel.position(idx);
                int res = readByteNode.execute(channel);

                // restore position
                channel.position(oldPos);

                return res;

            } catch (IOException e) {
                throw raise(PythonBuiltinClassType.OSError, e.getMessage());
            }
        }

        @Specialization
        Object doSlice(VirtualFrame frame, PMMap self, PSlice idx,
                        @Cached("create()") ReadFromChannelNode readNode,
                        @Cached("create()") InternalLenNode lenNode) {
            try {
                long len = lenNode.execute(frame, self);
                SliceInfo info = idx.computeIndices(PInt.intValueExact(len));
                SeekableByteChannel channel = self.getChannel();

                // save current position
                long oldPos = channel.position();

                channel.position(info.start);
                ByteSequenceStorage s = readNode.execute(channel, info.length);

                // restore position
                channel.position(oldPos);

                return factory().createBytes(s);
            } catch (IOException e) {
                throw raise(PythonBuiltinClassType.OSError, e.getMessage());
            }
        }

    }

    @Builtin(name = SpecialMethodNames.__SETITEM__, fixedNumOfPositionalArgs = 3)
    @GenerateNodeFactory
    abstract static class SetItemNode extends PythonBuiltinNode implements ByteWritingNode {

        @Specialization(guards = "!isPSlice(idxObj)")
        PNone doSingle(VirtualFrame frame, PMMap self, Object idxObj, Object val,
                        @Cached("createIndexError()") WriteByteToChannelNode writeByteNode,
                        @Cached("createExact()") CastToJavaLongNode castToLongNode,
                        @Cached("createCoerce()") CastToByteNode castToByteNode,
                        @Cached("create()") InternalLenNode lenNode,
                        @Cached("createBinaryProfile()") ConditionProfile outOfRangeProfile) {

            try {
                long i = castToLongNode.execute(idxObj);
                long len = lenNode.execute(frame, self);
                SeekableByteChannel channel = self.getChannel();
                long idx = i < 0 ? i + len : i;

                if (outOfRangeProfile.profile(idx < 0 || idx >= len)) {
                    throw raise(PythonBuiltinClassType.IndexError, "mmap index out of range");
                }

                // save current position
                long oldPos = channel.position();

                channel.position(idx);
                writeByteNode.execute(channel, castToByteNode.execute(val));

                // restore position
                channel.position(oldPos);

                return PNone.NONE;

            } catch (IOException e) {
                throw raise(PythonBuiltinClassType.OSError, e.getMessage());
            }
        }

        @Specialization
        PNone doSlice(VirtualFrame frame, PMMap self, PSlice idx, PIBytesLike val,
                        @Cached("create()") WriteToChannelNode writeNode,
                        @Cached("create()") SequenceNodes.GetSequenceStorageNode getStorageNode,
                        @Cached("create()") InternalLenNode lenNode,
                        @Cached("createBinaryProfile()") ConditionProfile invalidStepProfile) {

            try {
                long len = lenNode.execute(frame, self);
                SliceInfo info = idx.computeIndices(PInt.intValueExact(len));
                SeekableByteChannel channel = self.getChannel();

                if (invalidStepProfile.profile(info.step != 1)) {
                    throw raise(PythonBuiltinClassType.SystemError, "step != 1 not supported");
                }

                // save current position
                long oldPos = channel.position();

                channel.position(info.start);
                writeNode.execute(channel, getStorageNode.execute(val), info.length);

                // restore position
                channel.position(oldPos);

                return PNone.NONE;

            } catch (IOException e) {
                throw raise(PythonBuiltinClassType.OSError, e.getMessage());
            }
        }

        protected static CastToByteNode createCoerce() {
            return CastToByteNode.create(true);
        }
    }

    @Builtin(name = __LEN__, fixedNumOfPositionalArgs = 1)
    @GenerateNodeFactory
    public abstract static class LenNode extends PythonBuiltinNode {
        @Specialization
        long len(VirtualFrame frame, PMMap self,
                        @Cached("create()") InternalLenNode lenNode) {
            return lenNode.execute(frame, self);
        }
    }

    @Builtin(name = __ENTER__, fixedNumOfPositionalArgs = 1)
    @GenerateNodeFactory
    abstract static class EnterNode extends PythonUnaryBuiltinNode {
        @Specialization
        Object size(PMMap self) {
            return self;
        }
    }

    @Builtin(name = __EXIT__, fixedNumOfPositionalArgs = 4)
    @GenerateNodeFactory
    abstract static class ExitNode extends PythonBuiltinNode {
        protected static final String CLOSE = "close";

        @Specialization
        Object size(PMMap self, @SuppressWarnings("unused") Object typ, @SuppressWarnings("unused") Object val, @SuppressWarnings("unused") Object tb,
                        @Cached("create(CLOSE)") LookupAndCallUnaryNode callCloseNode) {
            return callCloseNode.executeObject(self);
        }
    }

    @Builtin(name = "close", fixedNumOfPositionalArgs = 1)
    @GenerateNodeFactory
    abstract static class CloseNode extends PythonUnaryBuiltinNode {

        @Specialization
        PNone close(PMMap self) {
            try {
                self.getChannel().close();
            } catch (IOException e) {
                // TODO(fa): ignore ?
            }
            return PNone.NONE;
        }
    }

    @Builtin(name = "closed", fixedNumOfPositionalArgs = 1, isGetter = true)
    @GenerateNodeFactory
    abstract static class ClosedNode extends PythonUnaryBuiltinNode {

        @Specialization
        boolean close(PMMap self) {
            return !self.getChannel().isOpen();
        }
    }

    @Builtin(name = "size", fixedNumOfPositionalArgs = 1)
    @GenerateNodeFactory
    abstract static class SizeNode extends PythonBuiltinNode {

        @Specialization
        long size(VirtualFrame frame, PMMap self,
                        @Cached("create()") InternalLenNode lenNode) {
            return lenNode.execute(frame, self);
        }
    }

    @Builtin(name = "tell", fixedNumOfPositionalArgs = 1)
    @GenerateNodeFactory
    abstract static class TellNode extends PythonBuiltinNode {
        @Specialization
        long readline(VirtualFrame frame, PMMap self) {

            try {
                SeekableByteChannel channel = self.getChannel();
                return channel.position() - self.getOffset();
            } catch (IOException e) {
                throw raiseOSError(frame, OSErrorEnum.EIO, e.getMessage());
            }
        }
    }

    @Builtin(name = "read_byte", fixedNumOfPositionalArgs = 1)
    @GenerateNodeFactory
    @TypeSystemReference(PythonArithmeticTypes.class)
    abstract static class ReadByteNode extends PythonUnaryBuiltinNode implements ByteReadingNode {

        @Specialization
        int readByte(PMMap self,
                        @Cached("createValueError()") ReadByteFromChannelNode readByteNode) {
            return readByteNode.execute(self.getChannel());
        }
    }

    @Builtin(name = "read", minNumOfPositionalArgs = 1, maxNumOfPositionalArgs = 2)
    @GenerateNodeFactory
    @TypeSystemReference(PythonArithmeticTypes.class)
    abstract static class ReadNode extends PythonBuiltinNode {

        @Specialization(guards = "!isNoValue(n)")
        PBytes read(PMMap self, @SuppressWarnings("unused") PNone n,
                        @Cached("create()") ReadFromChannelNode readChannelNode) {
            ByteSequenceStorage res = readChannelNode.execute(self.getChannel(), ReadFromChannelNode.MAX_READ);
            return factory().createBytes(res);
        }

        @Specialization(guards = "!isNoValue(n)")
        PBytes read(PMMap self, Object n,
                        @Cached("create()") ReadFromChannelNode readChannelNode,
                        @Cached("create()") CastToIndexNode castToIndexNode) {
            ByteSequenceStorage res = readChannelNode.execute(self.getChannel(), castToIndexNode.execute(n));
            return factory().createBytes(res);
        }

    }

    @Builtin(name = "readline", fixedNumOfPositionalArgs = 1)
    @GenerateNodeFactory
    abstract static class ReadlineNode extends PythonBuiltinNode {

        @Specialization
        Object readline(PMMap self,
                        @Cached("createAppend()") SequenceStorageNodes.AppendNode appendNode) {

            try {
                ByteBuffer buf = ByteBuffer.allocate(4096);
                SeekableByteChannel channel = self.getChannel();
                ByteSequenceStorage res = new ByteSequenceStorage(16);
                // search for newline char
                outer: while (readIntoBuffer(channel, buf) > 0) {
                    buf.flip();
                    while (buf.hasRemaining()) {
                        byte b = buf.get();
                        // CPython really tests for '\n' only
                        if (b != (byte) '\n') {
                            appendNode.execute(res, b);
                        } else {
                            // recover correct position (i.e. number of remaining bytes in buffer)
                            channel.position(channel.position() - buf.remaining() - 1);
                            break outer;
                        }
                    }
                    buf.clear();
                }
                return factory().createBytes(res);
            } catch (IOException e) {
                throw raise(PythonBuiltinClassType.OSError, e.getMessage());
            }
        }

        @TruffleBoundary(allowInlining = true)
        private static int readIntoBuffer(SeekableByteChannel ch, ByteBuffer dst) throws IOException {
            return ch.read(dst);
        }

        protected static SequenceStorageNodes.AppendNode createAppend() {
            return SequenceStorageNodes.AppendNode.create(() -> NoGeneralizationNode.create(CastToByteNode.INVALID_BYTE_VALUE));
        }
    }

    @Builtin(name = "seek", fixedNumOfPositionalArgs = 1)
    @GenerateNodeFactory
    @TypeSystemReference(PythonArithmeticTypes.class)
    abstract static class SeekNode extends PythonBuiltinNode {

        @Specialization(guards = "isNoValue(how)")
        Object seek(VirtualFrame frame, PMMap self, long dist, @SuppressWarnings("unused") PNone how) {
            return seek(frame, self, dist, 0);
        }

        @Specialization
        Object seek(VirtualFrame frame, PMMap self, long dist, int how) {
            try {
                return doSeek(self, dist, how);
            } catch (IOException e) {
                throw raiseOSError(frame, OSErrorEnum.EIO, e.getMessage());
            }
        }

        @TruffleBoundary
        private Object doSeek(PMMap self, long dist, int how) throws IOException {
            SeekableByteChannel channel = self.getChannel();
            long where;
            switch (how) {
                case 0: /* relative to start */
                    where = dist;
                    break;
                case 1: /* relative to current position */
                    where = channel.position() + dist;
                    break;
                case 2: /* relative to end */
                    long size;
                    if (self.getLength() == 0) {
                        size = channel.size() - self.getOffset();
                    } else {
                        size = self.getLength();
                    }
                    where = size + dist;
                    break;
                default:
                    throw raise(PythonBuiltinClassType.ValueError, "unknown seek type");
            }
            channel.position(where);
            return PNone.NONE;
        }
    }

    @Builtin(name = "find", minNumOfPositionalArgs = 2, maxNumOfPositionalArgs = 4)
    @GenerateNodeFactory
    @TypeSystemReference(PythonArithmeticTypes.class)
    public abstract static class FindNode extends PythonBuiltinNode implements ByteReadingNode {

        @Child private NormalizeIndexNode normalizeIndexNode;
        @Child private SequenceStorageNodes.GetItemNode getLeftItemNode;
        @Child private SequenceStorageNodes.GetItemNode getRightItemNode;

        public abstract long execute(PMMap bytes, Object sub, Object starting, Object ending);

        @Specialization
        long find(PMMap primary, PIBytesLike sub, Object starting, Object ending,
                        @Cached("createValueError()") ReadByteFromChannelNode readByteNode) {
            try {
                SeekableByteChannel channel = primary.getChannel();
                long len1 = channel.size();

                SequenceStorage needle = sub.getSequenceStorage();
                int len2 = needle.length();

                long s = castToLong(starting, 0);
                long e = castToLong(ending, len1);

                long start = s < 0 ? s + len1 : s;
                long end = e < 0 ? e + len1 : e;

                if (start >= len1 || len1 < len2) {
                    return -1;
                } else if (end > len1) {
                    end = len1;
                }

                // TODO implement a more efficient algorithm
                outer: for (long i = start; i < end; i++) {
                    // TODO(fa) don't seek but use circular buffer
                    channel.position(i);
                    for (int j = 0; j < len2; j++) {
                        int hb = readByteNode.execute(channel);
                        int nb = getGetRightItemNode().executeInt(needle, j);
                        if (nb != hb || i + j >= end) {
                            continue outer;
                        }
                    }
                    return i;
                }
                return -1;
            } catch (IOException e) {
                throw raise(PythonBuiltinClassType.OSError, e.getMessage());
            }
        }

        @Specialization
        long find(PMMap primary, int sub, Object starting, @SuppressWarnings("unused") Object ending,
                        @Cached("createValueError()") ReadByteFromChannelNode readByteNode) {
            try {
                SeekableByteChannel channel = primary.getChannel();
                long len1 = channel.size();

                long s = castToLong(starting, 0);
                long e = castToLong(ending, len1);

                long start = s < 0 ? s + len1 : s;
                long end = Math.max(e < 0 ? e + len1 : e, len1);

                channel.position(start);

                for (long i = start; i < end; i++) {
                    int hb = readByteNode.execute(channel);
                    if (hb == sub) {
                        return i;
                    }
                }
                return -1;
            } catch (IOException e) {
                throw raise(PythonBuiltinClassType.OSError, e.getMessage());
            }
        }

        // TODO(fa): use node
        private static long castToLong(Object obj, long defaultVal) {
            if (obj instanceof Integer || obj instanceof Long) {
                return ((Number) obj).longValue();
            } else if (obj instanceof PInt) {
                try {
                    return ((PInt) obj).longValueExact();
                } catch (ArithmeticException e) {
                    return defaultVal;
                }
            }
            return defaultVal;
        }

        private SequenceStorageNodes.GetItemNode getGetRightItemNode() {
            if (getRightItemNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                getRightItemNode = insert(SequenceStorageNodes.GetItemNode.create());
            }
            return getRightItemNode;
        }
    }

    abstract static class InternalLenNode extends PNodeWithContext {

        public abstract long execute(VirtualFrame frame, PMMap self);

        @Specialization(guards = "self.getLength() == 0")
        long doFull(VirtualFrame frame, PMMap self,
                        @Cached("create()") BranchProfile profile) {
            try {
                return self.getChannel().size() - self.getOffset();
            } catch (IOException e) {
                profile.enter();
                throw raiseOSError(frame, OSErrorEnum.EIO, e.getMessage());
            }
        }

        @Specialization(guards = "self.getLength() > 0")
        long doWindow(@SuppressWarnings("unused") VirtualFrame frame, PMMap self) {
            return self.getLength();
        }

        @Specialization
        long doGeneric(VirtualFrame frame, PMMap self) {
            if (self.getLength() == 0) {
                try {
                    return self.getChannel().size() - self.getOffset();
                } catch (IOException e) {
                    throw raiseOSError(frame, OSErrorEnum.EIO, e.getMessage());
                }
            }
            return self.getLength();
        }

        public static InternalLenNode create() {
            return InternalLenNodeGen.create();
        }
    }

}
