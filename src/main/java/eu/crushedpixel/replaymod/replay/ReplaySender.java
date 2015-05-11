package eu.crushedpixel.replaymod.replay;

import com.google.gson.Gson;
import eu.crushedpixel.replaymod.ReplayMod;
import eu.crushedpixel.replaymod.entities.CameraEntity;
import eu.crushedpixel.replaymod.events.RecordingHandler;
import eu.crushedpixel.replaymod.holders.KeyframeSet;
import eu.crushedpixel.replaymod.holders.PacketData;
import eu.crushedpixel.replaymod.holders.Position;
import eu.crushedpixel.replaymod.recording.ConnectionEventHandler;
import eu.crushedpixel.replaymod.recording.ReplayMetaData;
import eu.crushedpixel.replaymod.timer.MCTimerHandler;
import eu.crushedpixel.replaymod.utils.ReplayFileIO;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.ResourcePackRepository;
import net.minecraft.entity.Entity;
import net.minecraft.network.EnumConnectionState;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.*;
import net.minecraft.world.EnumDifficulty;
import net.minecraft.world.WorldSettings.GameType;
import net.minecraft.world.WorldType;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;

import java.io.*;
import java.net.URL;
import java.util.ArrayList;

@Sharable
public class ReplaySender extends ChannelInboundHandlerAdapter {

    private int currentTimeStamp;
    private boolean hurryToTimestamp;
    private long desiredTimeStamp = -1;
    private long toleratedTimeStamp = -1;
    private long lastTimeStamp, lastPacketSent;
    private boolean hasRestarted = false;
    private File replayFile;
    private boolean active = true;
    private ZipFile archive;
    private DataInputStream dis;
    private ChannelHandlerContext ctx = null;
    private boolean startFromBeginning = true;
    private NetworkManager networkManager;
    private boolean terminate = false;
    private double replaySpeed = 1f;
    private boolean hasWorldLoaded = false;
    private Minecraft mc = Minecraft.getMinecraft();
    private int replayLength = 0;
    private int actualID = -1;
    private ZipArchiveEntry replayEntry;
    private ArrayList<Class> badPackets = new ArrayList<Class>() {
        {
            add(S28PacketEffect.class);
            add(S2BPacketChangeGameState.class);
            add(S06PacketUpdateHealth.class);
            add(S2DPacketOpenWindow.class);
            add(S2EPacketCloseWindow.class);
            add(S2FPacketSetSlot.class);
            add(S30PacketWindowItems.class);
            add(S36PacketSignEditorOpen.class);
            add(S37PacketStatistics.class);
            add(S1FPacketSetExperience.class);
            add(S43PacketCamera.class);
            add(S39PacketPlayerAbilities.class);
        }
    };
    private boolean allowMovement = false;
    private Thread sender = new Thread(new Runnable() {

        @Override
        public void run() {
            try {
                dis = new DataInputStream(archive.getInputStream(replayEntry));
            } catch(Exception e) {
                e.printStackTrace();
            }

            int i=0;

            try {
                while(ctx == null && !terminate) {
                    Thread.sleep(10);
                }
                while(!terminate) {
                    if(startFromBeginning) {
                        System.out.println("start from beginning");
                        hasRestarted = true;
                        hasWorldLoaded = false;
                        currentTimeStamp = 0;
                        dis.close();
                        dis = new DataInputStream(archive.getInputStream(replayEntry));
                        startFromBeginning = false;
                        lastPacketSent = System.currentTimeMillis();
                        ReplayHandler.restartReplay();
                    }

                    while(!terminate && !startFromBeginning && (!paused() || !hasWorldLoaded)) {
                        //System.out.println("read");
                        try {
                            /*
							 * LOGIC:
							 * While behind desired timestamp, only send packets
							 * until desired timestamp is reached,
							 * then increase desired timestamp by 1/20th of a second
							 *
							 * Desired timestamp is divided through stretch factor.
							 *
							 * If hurrying, don't wait for correct timing.
							 */

                            if(!hurryToTimestamp && ReplayHandler.isInPath()) {
                                continue;
                            }

                            PacketData pd = ReplayFileIO.readPacketData(dis);

                            currentTimeStamp = pd.getTimestamp();

                            if(!ReplayHandler.isInPath() && !hurryToTimestamp && hasWorldLoaded) {
                                int timeWait = (int) Math.round((currentTimeStamp - lastTimeStamp) / replaySpeed);
                                long timeDiff = System.currentTimeMillis() - lastPacketSent;
                                lastPacketSent = System.currentTimeMillis();
                                long timeToSleep = Math.max(0, timeWait - timeDiff);
                                Thread.sleep(timeToSleep);
                            }

                            ReplaySender.this.channelRead(ctx, pd.getByteArray());

                            lastTimeStamp = currentTimeStamp;

                            if(hurryToTimestamp && currentTimeStamp >= desiredTimeStamp && !startFromBeginning) {
                                System.out.println("STOPPED HURRYING");
                                stopHurrying();
                                if(!ReplayHandler.isInPath() || hasRestarted) {
                                    MCTimerHandler.advanceRenderPartialTicks(5);
                                    MCTimerHandler.advancePartialTicks(5);
                                    MCTimerHandler.advanceTicks(5);
                                }
                                if(!ReplayHandler.isInPath()) {
                                    Position pos = ReplayHandler.getLastPosition();
                                    CameraEntity cam = ReplayHandler.getCameraEntity();
                                    if(cam != null) {
                                        if(Math.abs(pos.getX() - cam.posX) < ReplayMod.TP_DISTANCE_LIMIT && Math.abs(pos.getZ() - cam.posZ) < ReplayMod.TP_DISTANCE_LIMIT)
                                            if(pos != null) {
                                                cam.moveAbsolute(pos.getX(), pos.getY(), pos.getZ());
                                                cam.rotationPitch = pos.getPitch();
                                                cam.rotationYaw = pos.getYaw();
                                            }
                                    }
                                }
                                if(!ReplayHandler.isInPath()) {
                                    setReplaySpeed(0);
                                }
                                hasRestarted = false;
                            }

                        } catch(EOFException eof) {
                            dis = new DataInputStream(archive.getInputStream(replayEntry));
                            setReplaySpeed(0);
                        } catch(IOException e) {
                            e.printStackTrace();
                        }
                    }

                }
                System.out.println("STOPPED FOREVER");
            } catch(Exception e) {
                e.printStackTrace();
            }
        }
    });

    public ReplaySender(final File replayFile, NetworkManager nm) {
        this.replayFile = replayFile;
        this.networkManager = nm;
        if(("." + FilenameUtils.getExtension(replayFile.getAbsolutePath())).equals(ConnectionEventHandler.ZIP_FILE_EXTENSION)) {
            try {
                archive = new ZipFile(replayFile);
                replayEntry = archive.getEntry("recording" + ConnectionEventHandler.TEMP_FILE_EXTENSION);

                ZipArchiveEntry metadata = archive.getEntry("metaData" + ConnectionEventHandler.JSON_FILE_EXTENSION);
                InputStream is = archive.getInputStream(metadata);
                BufferedReader br = new BufferedReader(new InputStreamReader(is));

                String json = br.readLine();

                Gson gson = new Gson();
                ReplayMetaData metaData = gson.fromJson(json, ReplayMetaData.class);

                this.replayLength = metaData.getDuration();

                ZipArchiveEntry paths = archive.getEntry("paths");
                if(paths != null) {
                    InputStream is2 = archive.getInputStream(paths);
                    BufferedReader br2 = new BufferedReader(new InputStreamReader(is2));

                    String json2 = br2.readLine();
                    KeyframeSet[] repo = gson.fromJson(json2, KeyframeSet[].class);

                    ReplayHandler.setKeyframeRepository(repo, false);
                } else {
                    ReplayHandler.setKeyframeRepository(new KeyframeSet[]{}, false);
                }

                sender.start();
            } catch(Exception e) {
                e.printStackTrace();
            }
        }
    }

    public boolean isHurrying() {
        return hurryToTimestamp;
    }

    public int currentTimeStamp() {
        return currentTimeStamp;
    }

    public int replayLength() {
        return replayLength;
    }

    public void stopHurrying() {
        hurryToTimestamp = false;
    }

    public void terminateReplay() {
        terminate = true;
        try {
            channelInactive(ctx);
            ctx.channel().pipeline().close();
        } catch(Exception e) {
            e.printStackTrace();
        }
    }

    public long getDesiredTimestamp() {
        return desiredTimeStamp;
    }

    public void resetToleratedTimeStamp() {
        toleratedTimeStamp = -1;
    }

    public void jumpToTime(int millis) {
        System.out.println("Jumped to "+millis);
        if(!(ReplayHandler.isInPath() && ReplayProcess.isVideoRecording())) setReplaySpeed(replaySpeed);

        if((millis < currentTimeStamp && !isHurrying())) {
            if(ReplayHandler.isInPath()) {
                if(millis >= toleratedTimeStamp && toleratedTimeStamp >= 0) {
                    System.out.println("tolerated: "+toleratedTimeStamp);
                    return;
                }
            }
            startFromBeginning = true;
            System.out.println("has to start from beginning");
        }

        desiredTimeStamp = millis;
        System.out.println("Set desired Timestamp");
        if(ReplayHandler.isInPath()) {
            toleratedTimeStamp = millis;
        }
        hurryToTimestamp = true;
    }

    //private static Field dataWatcherField;

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg)
            throws Exception {
        if(terminate) {
            return;
        }

        if(ctx == null) {
            ctx = this.ctx;
        }

        if(msg instanceof Packet) {
            super.channelRead(ctx, msg);
            return;
        }
        byte[] ba = (byte[]) msg;

        try {
            Packet p = ReplayFileIO.deserializePacket(ba);

            if(p == null) return;

            //If hurrying, ignore some packets, unless during Replay Path and *not* in initial hurry
            if(hurryToTimestamp && (!ReplayHandler.isInPath() || (desiredTimeStamp - currentTimeStamp > 1000))) {
                if(p instanceof S45PacketTitle ||
                        p instanceof S2APacketParticles) return;

                if(p instanceof S0EPacketSpawnObject) {
                    S0EPacketSpawnObject pso = (S0EPacketSpawnObject)p;
                    int type = pso.func_148993_l();
                    if(type == 76) {
                        return;
                    }
                }
            }

            if(p instanceof S29PacketSoundEffect && ReplayHandler.isInPath() && ReplayProcess.isVideoRecording()) {
                return;
            }

            if(p instanceof S03PacketTimeUpdate) {
                p = TimeHandler.getTimePacket((S03PacketTimeUpdate) p);
            }

            if(p instanceof S48PacketResourcePackSend) {
                S48PacketResourcePackSend pa = (S48PacketResourcePackSend) p;
                Thread t = new ResourcePackCheck(pa.func_179783_a(), pa.func_179784_b());
                t.start();

                return;
            }

            if(badPackets.contains(p.getClass())) return;

			/*
			if(p instanceof S0EPacketSpawnObject) {
				if(mc.theWorld != null) {
					List<EntityArrow> arrows = mc.theWorld.getEntities(EntityArrow.class, new Predicate<EntityArrow>() {
						@Override
						public boolean apply(EntityArrow input) {
							return true;
						}
					});
 					if(arrows.size() > 20) {
						System.out.println(currentTimeStamp);
					}
				}
			}
			 */

            try {
                if(p instanceof S1CPacketEntityMetadata) {
                    S1CPacketEntityMetadata packet = (S1CPacketEntityMetadata) p;
                    if(packet.field_149379_a == actualID) {
                        packet.field_149379_a = RecordingHandler.entityID;
                    }
                }

                if(p instanceof S01PacketJoinGame) {
                    S01PacketJoinGame packet = (S01PacketJoinGame) p;
                    allowMovement = true;
                    int entId = packet.getEntityId();
                    actualID = entId;
                    entId = Integer.MIN_VALUE + 9002;
                    int dimension = packet.getDimension();
                    EnumDifficulty difficulty = packet.getDifficulty();
                    int maxPlayers = packet.getMaxPlayers();
                    WorldType worldType = packet.getWorldType();

                    p = new S01PacketJoinGame(entId, GameType.SPECTATOR, false, dimension,
                            difficulty, maxPlayers, worldType, false);
                }

                if(p instanceof S07PacketRespawn) {
                    S07PacketRespawn respawn = (S07PacketRespawn) p;
                    p = new S07PacketRespawn(respawn.func_149082_c(),
                            respawn.func_149081_d(), respawn.func_149080_f(), GameType.SPECTATOR);

                    allowMovement = true;
                }

				/*
				 * Proof of concept for some nasty player manipulation ;)
				String crPxl = "2cb08a5951f34e98bd0985d9747e80df";
				String johni = "cd3d4be14ffc2f9db432db09e0cd254b";

				if(p instanceof S38PacketPlayerListItem) {
					S38PacketPlayerListItem pp = (S38PacketPlayerListItem)p;
					if(((AddPlayerData)pp.func_179767_a().get(0)).func_179962_a().getId().toString().replace("-", "").equals(crPxl)) {
						GameProfile johniGP = new GameProfile(UUID.fromString(johni.replaceAll(
								"(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})",
								"$1-$2-$3-$4-$5")), "Johni0702");
						gameProfileField.set(pp.func_179767_a().get(0), johniGP);
						//pp.func_179767_a().set(0, johniGP);
						p = pp;
					}

				}

				if(p instanceof S0CPacketSpawnPlayer) {
					S0CPacketSpawnPlayer sp = (S0CPacketSpawnPlayer)p;

					if(sp.func_179819_c().toString().replace("-", "").equals(crPxl)) {
						playerUUIDField.set(sp, UUID.fromString(johni.replaceAll(
								"(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})",
								"$1-$2-$3-$4-$5")));
					}

					p = sp;
				}
				 */

				/*
				if(p instanceof S0CPacketSpawnPlayer) {
					System.out.println(dataWatcherField.get(p));
					System.out.println(((S0CPacketSpawnPlayer) p).func_148944_c());
				}
				 */

                if(p instanceof S08PacketPlayerPosLook) {
                    if(!hasWorldLoaded) hasWorldLoaded = true;
                    final S08PacketPlayerPosLook ppl = (S08PacketPlayerPosLook) p;

                    if(ReplayHandler.isInPath() && !hurryToTimestamp) return;

                    CameraEntity cent = ReplayHandler.getCameraEntity();

                    if(cent != null) {
                        if(!allowMovement && !((Math.abs(cent.posX - ppl.func_148932_c()) > ReplayMod.TP_DISTANCE_LIMIT) ||
                                (Math.abs(cent.posZ - ppl.func_148933_e()) > ReplayMod.TP_DISTANCE_LIMIT))) {
                            return;
                        } else {
                            allowMovement = false;
                        }
                    }

                    Thread t = new Thread(new Runnable() {

                        @Override
                        public void run() {
                            while(mc.theWorld == null) {
                                try {
                                    Thread.sleep(10);
                                } catch(InterruptedException e) {
                                    e.printStackTrace();
                                }
                            }

                            Entity ent = ReplayHandler.getCameraEntity();

                            if(ent == null || !(ent instanceof CameraEntity)) ent = new CameraEntity(mc.theWorld);
                            CameraEntity cent = (CameraEntity) ent;
                            cent.moveAbsolute(ppl.func_148932_c(), ppl.func_148928_d(), ppl.func_148933_e());

                            ReplayHandler.setCameraEntity(cent);
                        }
                    });

                    t.start();
                }

                if(p instanceof S43PacketCamera) {
                    return;
                }

                super.channelRead(ctx, p);
            } catch(Exception e) {
                System.out.println(p.getClass());
                e.printStackTrace();
            }

        } catch(Exception e) {
            e.printStackTrace();
        }

    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        this.ctx = ctx;
        networkManager.channel().attr(networkManager.attrKeyConnectionState).set(EnumConnectionState.PLAY);
        super.channelActive(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        archive.close();
        super.channelInactive(ctx);
    }

    public boolean paused() {
        return MCTimerHandler.getTimerSpeed() == 0;
    }

    public double getReplaySpeed() {
        if(!paused()) return replaySpeed;
        else return 0;
    }

    public void setReplaySpeed(final double d) {
        if(d != 0) this.replaySpeed = d;
        MCTimerHandler.setTimerSpeed((float) d);
    }

    public File getReplayFile() {
        return replayFile;
    }

    private static class ResourcePackCheck extends Thread {

        private static Minecraft mc = Minecraft.getMinecraft();
        private static ResourcePackRepository repo = mc.getResourcePackRepository();

        private String url, hash;

        public ResourcePackCheck(String url, String hash) {
            this.url = url;
            this.hash = hash;
        }

        private File getServerResourcePackLocation(String url, String hash) throws IOException, IllegalArgumentException, IllegalAccessException {

            String filename;

            if(hash.matches("^[a-f0-9]{40}$")) {
                filename = hash;
            } else {
                filename = url.substring(url.lastIndexOf("/") + 1);

                if(filename.contains("?")) {
                    filename = filename.substring(0, filename.indexOf("?"));
                }

                if(!filename.endsWith(".zip")) {
                    return null;
                }

                filename = "legacy_" + filename.replaceAll("\\W", "");
            }

            return new File(repo.dirServerResourcepacks, filename);
        }

        private boolean downloadServerResourcePack(String url, File file) {
            try {
                FileUtils.copyURLToFile(new URL(url), file);
                return true;
            } catch(Exception e) {
                e.printStackTrace();
            }
            return false;
        }

        @Override
        public void run() {
            try {
                boolean use = ReplayMod.instance.replaySettings.getUseResourcePacks();
                if(!use) return;

                System.out.println("Looking for downloaded Resource Pack...");
                File rp = getServerResourcePackLocation(url, hash);
                if(rp == null) {
                    System.out.println("Invalid Resource Pack provided");
                    return;
                }
                if(rp.exists()) {
                    System.out.println("Resource Pack found!");
                    repo.func_177319_a(rp);

                } else {
                    System.out.println("No Resource Pack found.");
                    System.out.println("Attempting to download Resource Pack...");
                    boolean success = downloadServerResourcePack(url, rp);
                    System.out.println(success ? "Resource pack was successfully downloaded!" : "Resource Pack download failed.");
                    if(success) {
                        repo.func_177319_a(rp);
                    }
                }

            } catch(Exception e) {
                e.printStackTrace();
            }
        }
    }

}
