/*
 	OrpheusMS: MapleStory Private Server based on OdinMS
    Copyright (C) 2012 Aaron Weiss <aaron@deviant-core.net>
    				Patrick Huy <patrick.huy@frz.cc>
					Matthias Butz <matze@odinms.de>
					Jan Christian Meyer <vimes@odinms.de>

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as
    published by the Free Software Foundation, either version 3 of the
    License, or (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
/*	
	Author : HaruTheHero Fixed by kevintjuh93
	Map(s): 		Aran Training Map 2
	Description: 		Quest - Help Kid
	Quest ID : 		21000
*/

importPackage(Packages.client);

var status = -1;

function start(mode, type, selection) {
    status++;
	if (mode != 1) {
	    if(type == 1 && mode == 0)
		    status -= 2;
		else{
			qm.sendNext("No, Aran... We can't leave a kid behind. I know it's a lot to ask, but please reconsider. Please!");
		    qm.dispose();
			return;
		}
	}
	if (status == 0)
			qm.sendAcceptDecline("Oh, no! I think there's still a child in the forest! Aran, I'm very sorry, but could you rescue the child? I know you're injured, but I don't have anyone else to ask!");
		else if (status == 1) {
			qm.forceStartQuest();
			qm.sendNext("#bThe Child is probably lost deep inside the forest!#k We have to escape before the Black Mage finds us. You must rush into the forest and bring the child back with you!");
		} else if (status == 2) {
			qm.sendNextPrev("Don't panic, Aran. If you wish to check the status of the \r\nquest, press #bQ #kand view the Quest window.");
		} else if (status == 3) {
			qm.sendNextPrev("Please, Aran! I'm begging you. I can't bear to lose another person to the Black Mage!");
		} else if (status == 4) {
			qm.showInfo("Effect/OnUserEff.img/guideEffect/aranTutorial/tutorialArrow1");
			qm.dispose();
		}
		}


function end(mode, type, selection) {
    status++;
	if (mode != 1) {
	    if(type == 1 && mode == 0)
		    status -= 2;
		else{
		    qm.dispose();
			return;
		}		
		if (status == 0) {
			qm.sendOk("Wah! *Sniff sniff* Hey, that's Aran! Are you here to rescue me?");
		} else if (status == 1) {
			qm.forceCompleteQuest();
			qm.dispose();
		}
	}
}