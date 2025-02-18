/**
 * Copyright (c) 2013-2024 Nikita Koksharov
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.redisson.client.handler;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import org.redisson.client.ChannelName;
import org.redisson.client.RedisClientConfig;
import org.redisson.client.RedisException;
import org.redisson.client.RedisPubSubConnection;
import org.redisson.client.codec.ByteArrayCodec;
import org.redisson.client.codec.Codec;
import org.redisson.client.codec.StringCodec;
import org.redisson.client.protocol.*;
import org.redisson.client.protocol.decoder.ListObjectDecoder;
import org.redisson.client.protocol.decoder.MultiDecoder;
import org.redisson.client.protocol.pubsub.Message;
import org.redisson.client.protocol.pubsub.PubSubMessage;
import org.redisson.client.protocol.pubsub.PubSubPatternMessage;
import org.redisson.client.protocol.pubsub.PubSubStatusMessage;
import org.redisson.misc.LogHelper;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Redis Publish Subscribe protocol decoder
 *
 * @author Nikita Koksharov
 *
 */
public class CommandPubSubDecoder extends CommandDecoder {

    private static final Set<String> UNSUBSCRIBE_COMMANDS = new HashSet<>(Arrays.asList(RedisCommands.PUNSUBSCRIBE.getName(), RedisCommands.UNSUBSCRIBE.getName(), RedisCommands.SUNSUBSCRIBE.getName()));
    private static final Set<String> SUBSCRIBE_COMMANDS = new HashSet<>(Arrays.asList(RedisCommands.PSUBSCRIBE.getName(), RedisCommands.SUBSCRIBE.getName(), RedisCommands.SSUBSCRIBE.getName()));
    private static final Set<String> MESSAGES = new HashSet<>(Arrays.asList("subscribe", "psubscribe", "punsubscribe", "unsubscribe", "ssubscribe", "sunsubscribe"));
    private static final Set<String> TYPE_MESSAGES = new HashSet<>(Arrays.asList("message", "smessage", "pmessage"));
    // It is not needed to use concurrent map because responses are coming consecutive
    private final Map<ChannelName, PubSubEntry> entries = new HashMap<>();
    private final Map<PubSubKey, CommandData<Object, Object>> commands = new ConcurrentHashMap<>();

    private final RedisClientConfig config;

    public CommandPubSubDecoder(RedisClientConfig config) {
        super(config.getAddress().getScheme());
        this.config = config;
    }

    public void addPubSubCommand(ChannelName channel, CommandData<Object, Object> data) {
        String operation = data.getCommand().getName().toLowerCase(Locale.ENGLISH);
        commands.put(new PubSubKey(channel, operation), data);
    }

    @Override
    protected QueueCommandHolder getCommand(ChannelHandlerContext ctx) {
        return ctx.channel().attr(CommandsQueuePubSub.CURRENT_COMMAND).get();
    }

    @Override
    protected void sendNext(Channel channel) {
        CommandsQueuePubSub handler = channel.pipeline().get(CommandsQueuePubSub.class);
        if (handler != null) {
            handler.sendNextCommand(channel);
        }
        state(null);
    }

    @Override
    protected void decodeCommand(Channel channel, ByteBuf in, QueueCommand data, int endIndex, State state) throws Exception {
        try {
            while (in.writerIndex() > in.readerIndex()) {
                if (data != null) {
                    if (((CommandData<Object, Object>) data).getPromise().isDone()) {
                        data = null;
                    }
                }
                decode(in, (CommandData<Object, Object>) data, null, channel, false, null, 0, state);
            }
            sendNext(channel, data);
        } catch (Exception e) {
            log.error("Unable to decode data. channel: {}, reply: {}", channel, LogHelper.toString(in), e);
            if (data != null) {
                data.tryFailure(e);
            }
            sendNext(channel);
            throw e;
        }
    }

    @Override
    protected void onError(Channel channel, String error) {
        Set<String> cmds = new HashSet<>(RedisCommands.PUBSUB_COMMANDS);
        cmds.remove(RedisCommands.SUBSCRIBE.getName());
        cmds.remove(RedisCommands.UNSUBSCRIBE.getName());

        String cmd = null;
        String e = error.toLowerCase(Locale.ENGLISH);
        for (String value : cmds) {
            if (e.contains(value.toLowerCase(Locale.ENGLISH))) {
                cmd = value;
                break;
            }
        }
        if (cmd == null) {
            if (e.contains(RedisCommands.UNSUBSCRIBE.getName().toLowerCase(Locale.ENGLISH))) {
                cmd = RedisCommands.UNSUBSCRIBE.getName();
            } else if (e.contains(RedisCommands.SUBSCRIBE.getName().toLowerCase(Locale.ENGLISH))) {
                cmd = RedisCommands.SUBSCRIBE.getName();
            }
        }

        if (cmd != null) {
            String c = cmd;
            commands.keySet().stream()
                    .filter(v -> v.getOperation().equalsIgnoreCase(c))
                    .forEach(v -> {
                        CommandData<Object, Object> dd = commands.get(v);
                        dd.getPromise().completeExceptionally(new RedisException(error));
                    });
        } else {
            super.onError(channel, error);
        }
    }

    @Override
    protected void decodeResult(CommandData<Object, Object> data, List<Object> parts, Channel channel,
            Object result) throws IOException {
        try {
            if (config.getExecutor().isShutdown()) {
                return;
            }
        } catch (IllegalStateException e) {
            // arise in JBOSS. skipped 
        }

        if (result instanceof Message) {
            checkpoint();

            RedisPubSubConnection pubSubConnection = RedisPubSubConnection.getFrom(channel);
            ChannelName channelName = ((Message) result).getChannel();
            if (result instanceof PubSubStatusMessage) {
                String operation = ((PubSubStatusMessage) result).getType().name().toLowerCase(Locale.ENGLISH);
                PubSubKey key = new PubSubKey(channelName, operation);
                CommandData<Object, Object> d = commands.get(key);
                if (SUBSCRIBE_COMMANDS.contains(d.getCommand().getName())) {
                    commands.remove(key);
                    entries.put(channelName, new PubSubEntry(d.getMessageDecoder()));
                }
                
                if (UNSUBSCRIBE_COMMANDS.contains(d.getCommand().getName())) {
                    commands.remove(key);
                    if (result instanceof PubSubPatternMessage) {
                        channelName = ((PubSubPatternMessage) result).getPattern();
                    }
                    PubSubEntry entry = entries.remove(channelName);
                    if (config.isKeepPubSubOrder()) {
                        enqueueMessage(result, pubSubConnection, entry);
                    }
                }
            }

            if (config.isKeepPubSubOrder()) {
                if (result instanceof PubSubPatternMessage) {
                    channelName = ((PubSubPatternMessage) result).getPattern();
                }
                PubSubEntry entry = entries.get(channelName);
                if (entry != null) {
                    enqueueMessage(result, pubSubConnection, entry);
                }
            } else {
                config.getExecutor().execute(new Runnable() {
                    @Override
                    public void run() {
                        if (result instanceof PubSubStatusMessage) {
                            pubSubConnection.onMessage((PubSubStatusMessage) result);
                        } else if (result instanceof PubSubMessage) {
                            pubSubConnection.onMessage((PubSubMessage) result);
                        } else if (result instanceof PubSubPatternMessage) {
                            pubSubConnection.onMessage((PubSubPatternMessage) result);
                        }
                    }
                });
            }
        } else {
            super.decodeResult(data, parts, channel, result);
        }
    }

    private void enqueueMessage(Object res, RedisPubSubConnection pubSubConnection, PubSubEntry entry) {
        if (res != null) {
            entry.getQueue().add((Message) res);
        }
        
        if (!entry.getSent().compareAndSet(false, true)) {
            return;
        }
        
        config.getExecutor().execute(() -> {
            try {
                while (true) {
                    Message result = entry.getQueue().poll();
                    if (result != null) {
                        if (result instanceof PubSubStatusMessage) {
                            pubSubConnection.onMessage((PubSubStatusMessage) result);
                        } else if (result instanceof PubSubMessage) {
                            pubSubConnection.onMessage((PubSubMessage) result);
                        } else if (result instanceof PubSubPatternMessage) {
                            pubSubConnection.onMessage((PubSubPatternMessage) result);
                        }
                    } else {
                        break;
                    }
                }
            } finally {
                entry.getSent().set(false);
                if (!entry.getQueue().isEmpty()) {
                    enqueueMessage(null, pubSubConnection, entry);
                }
            }
        });
    }
    
    @Override
    protected MultiDecoder<Object> messageDecoder(CommandData<Object, Object> data, List<Object> parts) {
        if (parts.isEmpty() || parts.get(0) == null) {
            return null;
        }

        if ("invalidate".equals(parts.get(0))) {
            parts.set(0, "message");
            parts.add(1, ChannelName.TRACKING.getName());
        }

        String command = parts.get(0).toString();
        if (MESSAGES.contains(command)) {
            ChannelName channelName = new ChannelName((byte[]) parts.get(1));
            PubSubKey key = new PubSubKey(channelName, command);
            CommandData<Object, Object> commandData = commands.get(key);
            if (commandData == null) {
                return null;
            }
            return commandData.getCommand().getReplayMultiDecoder();
        } else if (TYPE_MESSAGES.contains(command)) {
            byte[] channelName = (byte[]) parts.get(1);
            PubSubEntry entry = entries.get(new ChannelName(channelName));
            if (entry == null) {
                return null;
            }
            return entry.getDecoder();
        } else if ("pong".equals(command) || data == null) {
            return new ListObjectDecoder<>(0);
        }

        return data.getCommand().getReplayMultiDecoder();
    }

    @Override
    protected Decoder<Object> selectDecoder(CommandData<Object, Object> data, List<Object> parts, long size, State state) {
        if (parts != null) {
            if (data != null && parts.size() == 1 && "pong".equals(parts.get(0))) {
                return data.getCodec().getValueDecoder();
            }
            if (parts.size() == 1) {
                return ByteArrayCodec.INSTANCE.getValueDecoder();
            }
            if (parts.size() == 2 && "pmessage".equals(parts.get(0))) {
                return ByteArrayCodec.INSTANCE.getValueDecoder();
            }
            if (parts.size() == 2 && TYPE_MESSAGES.contains(parts.get(0))) {
                byte[] channelName = (byte[]) parts.get(1);
                return getDecoder(null, parts, channelName, size);
            }
            if (parts.size() == 3 && "pmessage".equals(parts.get(0))) {
                byte[] patternName = (byte[]) parts.get(1);
                return getDecoder(null, parts, patternName, size);
            }
        }
        
        if (data != null && data.getCommand().getName().equals(RedisCommands.PING.getName())) {
            return StringCodec.INSTANCE.getValueDecoder();
        }
        
        return super.selectDecoder(data, parts, size, state);
    }

    private Decoder<Object> getDecoder(Codec codec, List<Object> parts, byte[] name, long size) {
        PubSubEntry entry = entries.get(new ChannelName(name));
        if (entry != null) {
            return entry.getDecoder().getDecoder(codec, parts.size(), state(), size);
        }
        return ByteArrayCodec.INSTANCE.getValueDecoder();
    }

}
