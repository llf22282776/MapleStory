/*
	This file is part of the OdinMS Maple Story Server
    Copyright (C) 2008 Patrick Huy <patrick.huy@frz.cc>
		       Matthias Butz <matze@odinms.de>
		       Jan Christian Meyer <vimes@odinms.de>

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as
    published by the Free Software Foundation version 3 as published by
    the Free Software Foundation. You may not use, modify or distribute
    this program under any other version of the GNU Affero General Public
    License.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/
package net.channel.handler;

import server.MapleInventoryManipulator;
import server.MapleItemInformationProvider;
import java.rmi.RemoteException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import client.BuddylistEntry;
import client.CharacterNameAndId;
import client.MapleCharacter;
import client.MapleClient;
import client.MapleInventoryType;
import client.MapleQuestStatus;
import client.SkillFactory;
import constants.skills.SuperGM;
import java.sql.SQLException;
import java.util.List;
import client.ISkill;
import tools.DatabaseConnection;
import net.AbstractMaplePacketHandler;
import net.channel.ChannelServer;
import net.world.CharacterIdChannelPair;
import net.world.MaplePartyCharacter;
import net.world.PartyOperation;
import net.world.PlayerBuffValueHolder;
import net.world.guild.MapleAlliance;
import net.world.guild.MapleGuild;
import net.world.remote.WorldChannelInterface;
import tools.MaplePacketCreator;
import tools.data.input.SeekableLittleEndianAccessor;
import client.Equip;
import scripting.npc.NPCScriptManager;
import constants.ServerConstants;
import provider.MapleData;
import provider.MapleDataProvider;
import provider.MapleDataProviderFactory;
import provider.MapleDataTool;
import java.io.File;

public final class PlayerLoggedinHandler extends AbstractMaplePacketHandler {
    @Override
    public final boolean validateState(MapleClient c) {
        return !c.isLoggedIn();
    }
    public final void handlePacket(SeekableLittleEndianAccessor slea, MapleClient c) {
        int cid = slea.readInt();
        MapleCharacter player = null;
        try {
            player = MapleCharacter.loadCharFromDB(cid, c, true);
        } catch (SQLException e) {
            e.printStackTrace();
        }
        c.setPlayer(player);
        c.setAccID(player.getAccountID());
        int state = c.getLoginState();
        boolean allowLogin = true;
        ChannelServer cserv = c.getChannelServer();
        synchronized (this) {
            try {
                WorldChannelInterface worldInterface = cserv.getWorldInterface();
                if (state == MapleClient.LOGIN_SERVER_TRANSITION) {
                    for (String charName : c.loadCharacterNames(c.getWorld())) {
                        if (worldInterface.isConnected(charName)) {
                            allowLogin = false;
                            break;
                        }
                    }
                }
            } catch (RemoteException e) {
                cserv.reconnectWorld();
                allowLogin = false;
            }

            if (state != MapleClient.LOGIN_SERVER_TRANSITION || !allowLogin) {
                c.setPlayer(null);
                c.getSession().close(true);
                return;
            }
            c.updateLoginState(MapleClient.LOGIN_LOGGEDIN);
        }
        cserv.addPlayer(player);
        try {
            List<PlayerBuffValueHolder> buffs = cserv.getWorldInterface().getBuffsFromStorage(cid);
            if (buffs != null) {
                c.getPlayer().silentGiveBuffs(buffs);
            }
        } catch (RemoteException e) {
            cserv.reconnectWorld();
        }
        Connection con = DatabaseConnection.getConnection();
        try {
            PreparedStatement ps = con.prepareStatement("SELECT SkillID,StartTime,length FROM cooldowns WHERE charid = ?");
            ps.setInt(1, c.getPlayer().getId());
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                final int skillid = rs.getInt("SkillID");
                final long length = rs.getLong("length"), startTime = rs.getLong("StartTime");
                if (length + startTime < System.currentTimeMillis()) {
                    continue;
                }
                c.getPlayer().giveCoolDowns(skillid, startTime, length);
            }
            rs.close();
            ps.close();
            ps = con.prepareStatement("DELETE FROM cooldowns WHERE charid = ?");
            ps.setInt(1, c.getPlayer().getId());
            ps.executeUpdate();
            ps.close();
            ps = con.prepareStatement("SELECT Mesos FROM dueypackages WHERE RecieverId = ? and Checked = 1");
            ps.setInt(1, c.getPlayer().getId());
            rs = ps.executeQuery();
            if (rs.next()) {
                try {
                    PreparedStatement pss = DatabaseConnection.getConnection().prepareStatement("UPDATE dueypackages SET Checked = 0 where RecieverId = ?");
                    pss.setInt(1, c.getPlayer().getId());
                    pss.executeUpdate();
                    pss.close();
                } catch (SQLException e) {
                }
                c.announce(MaplePacketCreator.sendDueyMSG((byte) 0x1B));
            }
            rs.close();
            ps.close();
        } catch (SQLException e) {
            e.printStackTrace();
        }
        c.announce(MaplePacketCreator.getCharInfo(player));
        player.sendKeymap();
        c.getPlayer().sendMacros();
        player.getMap().addPlayer(player);
        try {
            int buddyIds[] = player.getBuddylist().getBuddyIds();
            cserv.getWorldInterface().loggedOn(player.getName(), player.getId(), c.getChannel(), buddyIds);
            for (CharacterIdChannelPair onlineBuddy : cserv.getWorldInterface().multiBuddyFind(player.getId(), buddyIds)) {
                BuddylistEntry ble = player.getBuddylist().get(onlineBuddy.getCharacterId());
                ble.setChannel(onlineBuddy.getChannel());
                player.getBuddylist().put(ble);
            }
            c.announce(MaplePacketCreator.updateBuddylist(player.getBuddylist().getBuddies()));
        } catch (RemoteException e) {
            cserv.reconnectWorld();
        }
        c.announce(MaplePacketCreator.loadFamily(player));
        if (player.getFamilyId() > 0) {
            c.announce(MaplePacketCreator.getFamilyInfo(player));
        }
        if (player.getGuildId() > 0) {
            try {
                MapleGuild playerGuild = cserv.getWorldInterface().getGuild(player.getGuildId(), player.getMGC());
                if (playerGuild == null) {
                    player.deleteGuild(player.getGuildId());
                    player.resetMGC();
                    player.setGuildId(0);
                } else {
                    cserv.getWorldInterface().setGuildMemberOnline(player.getMGC(), true, c.getChannel());
                    c.announce(MaplePacketCreator.showGuildInfo(player));
                    int allianceId = player.getGuild().getAllianceId();
                    if (allianceId > 0) {
                        MapleAlliance newAlliance = cserv.getWorldInterface().getAlliance(allianceId);
                        if (newAlliance == null) {
                            newAlliance = MapleAlliance.loadAlliance(allianceId);
                            if (newAlliance != null) {
                                cserv.getWorldInterface().addAlliance(allianceId, newAlliance);
                            } else {
                                player.getGuild().setAllianceId(0);
                            }
                        }
                        if (newAlliance != null) {
                            c.announce(MaplePacketCreator.getAllianceInfo(newAlliance));
                            c.announce(MaplePacketCreator.getGuildAlliances(newAlliance, c));
                            cserv.getWorldInterface().allianceMessage(allianceId, MaplePacketCreator.allianceMemberOnline(player, true), player.getId(), -1);
                        }
                    }
                }
            } catch (RemoteException e) {
                cserv.reconnectWorld();
            }
        }
        try {
            c.getPlayer().showNote();
            if (player.getParty() != null) {
                cserv.getWorldInterface().updateParty(player.getParty().getId(), PartyOperation.LOG_ONOFF, new MaplePartyCharacter(player));
            }
            player.updatePartyMemberHP();
        } catch (RemoteException e) {
            cserv.reconnectWorld();
        }
        for (MapleQuestStatus status : player.getStartedQuests()) {
            if (status.hasMobKills()) {
                c.announce(MaplePacketCreator.updateQuestMobKills(status));
            }
        }
        CharacterNameAndId pendingBuddyRequest = player.getBuddylist().pollPendingRequest();
        if (pendingBuddyRequest != null) {
            player.getBuddylist().put(new BuddylistEntry(pendingBuddyRequest.getName(), "Default Group", pendingBuddyRequest.getId(), -1, false));
            c.announce(MaplePacketCreator.requestBuddylistAdd(pendingBuddyRequest.getId(), c.getPlayer().getId(), pendingBuddyRequest.getName()));
        }
        if (player.getInventory(MapleInventoryType.EQUIPPED).findById(1122017) != null) {
            player.equipPendantOfSpirit();
        }
        c.announce(MaplePacketCreator.updateBuddylist(player.getBuddylist().getBuddies()));
        c.announce(MaplePacketCreator.updateGender(player));
        player.checkMessenger();
        Equip nEquip = (Equip) player.getInventory(MapleInventoryType.EQUIPPED).getItem((byte) -11);
        if (nEquip.getLevel() == 0) {
        nEquip.setLevel((byte)1);
        }

            if (!c.messageOn()) { // only happens on login!
            if (player.isGM()) {
            player.dropMessage("Welcome back, " + player.getName() + ". Currently "  + c.getChannelServer().getConnectedClients() + " client(s) are connected.");
            }
            int expChance=(int)(ServerConstants.exp_bonus_chance*Math.random());
            if (expChance == 1 && player.getExpBonuses() <= 4 && ServerConstants.exp_bonuses == true) {
            c.getChannelServer().yellowWorldMessage("[" + player.getName() + "] Has recieved a 150% exp bonus!");
            player.addExpBonus();
            if (ServerConstants.max_skills == true) {
            for (MapleData skill_ : MapleDataProviderFactory.getDataProvider(new File(System.getProperty("wzpath") + "/" + "String.wz")).getData("Skill.img").getChildren()) {
                try {
                    ISkill skill = SkillFactory.getSkill(Integer.parseInt(skill_.getName()));
                    player.changeSkillLevel(skill, skill.getMaxLevel(), skill.getMaxLevel() , -1);
                } catch (NumberFormatException nfe) {
                    break;
                } catch (NullPointerException npe) {
                    continue;
                }

            }
            } else {
            NPCScriptManager.getInstance().getCM(c).maxMastery();
            }
            }
            if (!player.isGM() && ServerConstants.login_announcing == true && expChance > 1 && player.getLevel() > 1) {
            //c.getChannelServer().yellowWorldMessage("[" + c.getPlayer().getName() + "] Has logged in!");
            }
                c.setMessageToggle(1);
}

        if (player.getLevel() == 1 && player.getMap().getId() == 100000000 && player.getVotePoints() == 0) {
        c.getChannelServer().yellowWorldMessage("[" + c.getPlayer().getName() + "] Has joined our server!");
        player.dropOverheadMessage("#eWelcome to #r" + ServerConstants.SERVERNAME + "#k! In celebration of you joining us today, you get 1 vote point and a 30 minute EXPBONUS.");
        player.gainVotePoints(1);
        SkillFactory.getSkill(SuperGM.HOLY_SYMBOL).getEffect(SkillFactory.getSkill(SuperGM.HOLY_SYMBOL).getMaxLevel()).applyTo(player);
        }
        c.announce(MaplePacketCreator.enableReport());
        player.changeSkillLevel(SkillFactory.getSkill(10000000 * player.getJobType() + 12), player.getLinkedLevel() / 10, 20, -1);
        player.checkBerserk();
        player.expirationTask();
        player.setRates();
    }
}