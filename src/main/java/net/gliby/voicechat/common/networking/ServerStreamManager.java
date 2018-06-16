package net.gliby.voicechat.common.networking;

import net.gliby.voicechat.common.VoiceChatServer;
import net.gliby.voicechat.common.api.VoiceChatAPI;
import net.gliby.voicechat.common.api.events.ServerStreamEvent;
import net.gliby.voicechat.common.networking.entityhandler.EntityHandler;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.fml.common.FMLCommonHandler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public class ServerStreamManager
{
    List<ServerStream> currentStreams;
    ConcurrentLinkedQueue<ServerDatalet> dataQueue;
    public ConcurrentHashMap<Integer, ServerStream> streaming;
    public HashMap<UUID, Integer> chatModeMap;
    private HashMap receivedEntityData;
    private Thread threadUpdate;
    private Thread treadQueue;
    private final VoiceChatServer voiceChat;
    public List mutedPlayers;
    public EntityHandler entityHandler;
    volatile boolean running;

    ServerStreamManager(VoiceChatServer voiceChat)
    {
        this.voiceChat = voiceChat;
    }

    public void addQueue(EntityPlayerMP player, byte[] decoded_data, byte divider, int id, boolean end)
    {
        if (!this.mutedPlayers.contains(player.getPersistentID()))
        {
            this.dataQueue.offer(new ServerDatalet(player, id, decoded_data, divider, end, (byte)-1));

            synchronized (this.treadQueue)
            {
                this.treadQueue.notify();
            }
        }
    }

    private void addStreamSafe(ServerStream stream)
    {
        this.streaming.put(stream.id, stream);
        this.currentStreams.add(stream);

        synchronized (this.threadUpdate)
        {
            this.threadUpdate.notify();
        }
    }

    void createStream(ServerDatalet data)
    {
        ServerStream stream;
        this.addStreamSafe(stream = new ServerStream(data.player, data.id, this.generateSource(data)));
        VoiceChatAPI.instance().bus().post(new ServerStreamEvent.StreamCreated(this, stream, data));
        this.giveStream(stream, data);
    }

    void feedStreamToAllPlayers(ServerStream stream, ServerDatalet voiceData)
    {
        EntityPlayerMP speaker = voiceData.player;
        List players = FMLCommonHandler.instance().getMinecraftServerInstance().getEntityWorld().playerEntities;
        int i;
        EntityPlayerMP target;

        if (voiceData.end)
        {
            for (i = 0; i < players.size(); ++i)
            {
                target = (EntityPlayerMP)players.get(i);

                if (target.getEntityId() != speaker.getEntityId())
                {
                    this.voiceChat.getVoiceServer().sendVoiceEnd(target, voiceData.id);
                }
            }
        }
        else {
            for (i = 0; i < players.size(); ++i)
            {
                target = (EntityPlayerMP)players.get(i);

                if (target.getEntityId() != speaker.getEntityId())
                {
                    this.entityHandler.whileSpeaking(stream, speaker, target);
                    this.voiceChat.getVoiceServer().sendChunkVoiceData(target, voiceData.id, false, voiceData.data, voiceData.divider, voiceData.volume);
                }
            }
        }
    }

    public void feedStreamToPlayer(ServerStream stream, ServerDatalet voiceData, EntityPlayerMP target, boolean direct)
    {
        EntityPlayerMP speaker = voiceData.player;

        if (voiceData.end)
        {
            if (this.voiceChat.getVoiceServer() != null && target != null)
            {
                this.voiceChat.getVoiceServer().sendVoiceEnd(target, stream.id);
            }
            else {
                this.entityHandler.whileSpeaking(stream, speaker, target);
                this.voiceChat.getVoiceServer().sendChunkVoiceData(target, voiceData.id, direct, voiceData.data, voiceData.divider, voiceData.volume);
            }
        }
    }

    void feedStreamToWorld(ServerStream stream, ServerDatalet voiceData)
    {
        EntityPlayerMP speaker = voiceData.player;
        List players = speaker.world.playerEntities;
        int i;
        EntityPlayerMP target;

        if (voiceData.end)
        {
            for (i = 0; i < players.size(); ++i)
            {
                target = (EntityPlayerMP)players.get(i);

                if (target.getEntityId() != speaker.getEntityId() && this.voiceChat.getVoiceServer() != null)
                {
                    this.voiceChat.getVoiceServer().sendVoiceEnd(target, stream.id);
                }
            }
        }
        else {
            for (i = 0; i < players.size(); ++i)
            {
                target = (EntityPlayerMP)players.get(i);

                if (target.getEntityId() != speaker.getEntityId())
                {
                    this.entityHandler.whileSpeaking(stream, speaker, target);
                    this.voiceChat.getVoiceServer().sendChunkVoiceData(target, voiceData.id, false, voiceData.data, voiceData.divider, voiceData.volume);
                }
            }
        }
    }

    void feedWithinEntityWithRadius(ServerStream stream, ServerDatalet voiceData, int distance)
    {
        EntityPlayerMP speaker = stream.player;
        List players = speaker.world.playerEntities;
        int i;
        EntityPlayerMP target;
        double d4;
        double d5;
        double d6;

        if (voiceData.end)
        {
            for (i = 0; i < players.size(); ++i)
            {
                target = (EntityPlayerMP)players.get(i);

                if (target.getEntityId() != speaker.getEntityId())
                {
                    d4 = speaker.posX - target.posX;
                    d5 = speaker.posY - target.posY;
                    d6 = speaker.posZ - target.posZ;

                    if (d4 * d4 + d5 * d5 + d6 * d6 < (double) (distance * distance) && this.voiceChat.getVoiceServer() != null)
                    {
                        this.voiceChat.getVoiceServer().sendVoiceEnd(target, stream.id);
                    }
                }
            }
        }
        else {
            for (i = 0; i < players.size(); ++i)
            {
                target = (EntityPlayerMP)players.get(i);

                if (target.getEntityId() != speaker.getEntityId())
                {
                    d4 = speaker.posX - target.posX;
                    d5 = speaker.posY - target.posY;
                    d6 = speaker.posZ - target.posZ;
                    double distanceBetween = d4 * d4 + d5 * d5 + d6 * d6;

                    if (distanceBetween < (double) (distance * distance))
                    {
                        this.entityHandler.whileSpeaking(stream, speaker, target);
                        this.voiceChat.getVoiceServer().sendChunkVoiceData(target, voiceData.id, true, voiceData.data, voiceData.divider, voiceData.volume);

                        if (stream.tick % this.voiceChat.serverSettings.positionUpdateRate == 0)
                        {
                            if (distanceBetween > 4096.0D)
                            {
                                this.voiceChat.getVoiceServer().sendEntityPosition(target, speaker.getEntityId(), speaker.posX, speaker.posY, speaker.posZ);
                            }

                            stream.tick = 0;
                        }
                        ++stream.tick;
                    }
                }
            }
        }
    }

    private String generateSource(ServerDatalet let)
    {
        return Integer.toString(let.id);
    }

    public ServerStream getStream(int entityId)
    {
        return (ServerStream) this.streaming.get(entityId);
    }

    public void giveEntity(EntityPlayerMP receiver, EntityPlayerMP speaker)
    {
        this.voiceChat.getServerNetwork().sendEntityData(receiver, speaker.getEntityId(), speaker.getName(), speaker.posX, speaker.posY, speaker.posZ);
    }

    void giveStream(ServerStream stream, ServerDatalet let)
    {
        VoiceChatAPI.instance().bus().post(new ServerStreamEvent.StreamFeed(this, stream, let));
        stream.lastUpdated = System.currentTimeMillis();

        if (let.end)
        {
            this.killStream(stream);
        }
    }

    public void init()
    {
        this.running = true;
        this.entityHandler = new EntityHandler(this.voiceChat);
        this.mutedPlayers = new ArrayList();
        this.dataQueue = new ConcurrentLinkedQueue();
        this.currentStreams = new ArrayList();
        this.streaming = new ConcurrentHashMap();
        this.chatModeMap = new HashMap<>();
        this.receivedEntityData = new HashMap<>();
        this.treadQueue = new Thread(new ThreadDataQueue(this), "Stream Queue");
        this.treadQueue.start();
        this.threadUpdate = new Thread(new ThreadDataUpdateStream(this), "Stream Update");
        this.threadUpdate.start();
    }

    public void killStream(ServerStream stream)
    {
        this.currentStreams.remove(stream);
        this.streaming.remove(stream.id);
        VoiceChatAPI.instance().bus().post(new ServerStreamEvent.StreamDestroyed(this, stream));
    }

    ServerStream newDatalet(ServerDatalet let)
    {
        return (ServerStream)this.streaming.get(let.id);
    }

    void reset()
    {
        this.running = false;
        this.currentStreams.clear();
        this.chatModeMap.clear();
        this.dataQueue.clear();
        this.mutedPlayers.clear();
        this.receivedEntityData.clear();
        this.streaming.clear();
    }
}