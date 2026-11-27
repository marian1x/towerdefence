package com.marian1x.agesofsiege.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marian1x.agesofsiege.R
import com.marian1x.agesofsiege.core.Content
import com.marian1x.agesofsiege.game.GameController
import com.marian1x.agesofsiege.game.Overlay
import com.marian1x.agesofsiege.game.Screen

internal fun unitIcon(id: String) = when (id) {
    "bowman" -> R.drawable.ic_unit_bowman
    "knight" -> R.drawable.ic_unit_knight
    else -> R.drawable.ic_unit_swordsman
}

@Composable
fun ArmyPanel(c: GameController) {
    val hud = c.hud
    val total = hud.garrison.values.sum() + hud.away
    GameDialog("Army", width = 560, onClose = { hud.overlay = Overlay.NONE }) {
        Text(
            "$total / ${hud.armyCap} soldiers" + (if (hud.away > 0) " · ${hud.away} on expedition" else ""),
            color = Color.White, fontSize = 15.sp,
        )
        Text(
            "Soldiers at home guard the Keep and fight raiders. Send them from the World map to explore, raid camps and invade your rival.",
            color = TextDim, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp),
        )
        if (!hud.hasBarracks) {
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color(0x33FFC94D)).padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Build a Barracks to recruit troops.", color = Gold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                MenuButton("BUILD", primary = true) {
                    hud.overlay = Overlay.NONE
                    c.selectBuild("barracks")
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        for (u in Content.units) {
            val owned = hud.garrison[u.id] ?: 0
            val affordable = hud.gold >= u.cost.gold && hud.wood >= u.cost.wood && hud.stone >= u.cost.stone
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF232A39)).padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(painterResource(unitIcon(u.id)), u.name, Modifier.size(64.dp))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(u.name, fontFamily = GameFont, fontSize = 18.sp, color = Color.White)
                        Spacer(Modifier.width(8.dp))
                        Text("×$owned at home", color = Gold, fontSize = 13.sp)
                    }
                    Text(u.description, color = TextDim, fontSize = 12.sp)
                    Text(
                        "HP ${u.hp.toInt()} · DMG ${u.damage.toInt()} · ${if (u.range > 1.5f) "ranged" else "melee"} · power ${u.power.toInt()}",
                        color = Color.White, fontSize = 12.sp,
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    MenuButton(
                        "RECRUIT", enabled = hud.hasBarracks && affordable && total < hud.armyCap,
                        primary = true,
                    ) { c.recruit(u.id) }
                    Spacer(Modifier.height(4.dp))
                    CostRow(u.cost, affordable, fontSize = 12)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MenuButton("CLOSE", Modifier.weight(1f)) { hud.overlay = Overlay.NONE }
            MenuButton("WORLD MAP", Modifier.weight(1f), primary = true) {
                hud.overlay = Overlay.NONE
                hud.screen = Screen.WORLD
            }
        }
    }
}
