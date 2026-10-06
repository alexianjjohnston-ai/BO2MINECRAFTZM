"""Writes sheets/bo2_models.json: which Black Ops II models the game uses, by the names OpenAssetTools gives them.
The models themselves are NEVER stored in this repo: the game converts them on the player's PC from their own install (bo2/Bo2Assets).
group: gun (xmodel = first-person model, world = third-person/dropped model), zombie, head, machine, prop, perkbottle, player."""
import json, os
SH = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'sheets')

rows = []
def add(id, group, xmodel, world="", note=""):
    rows.append(dict(id=id, group=group, xmodel=xmodel, world=world, note=note))

def gun(id, stem, note=""):
    add(id, "gun", f"t6_wpn_{stem}_view", f"t6_wpn_{stem}_world", note)

gun("m1911", "pistol_m1911")
gun("rottweil72", "shotty_olympia")
gun("mp5k", "smg_mp5")
gun("ak74u", "smg_ak74u")
gun("m14", "ar_m14")
gun("m16", "ar_m16a2")
gun("galil", "ar_galil")
gun("python", "pistol_python")
gun("ray_gun", "zmb_raygun2")
gun("ray_gun_pap", "zmb_raygun2_upg")

add("zombie_a", "zombie", "c_zom_zombie1_body01", note="clothed zombie, straitjacket")
add("zombie_b", "zombie", "c_zom_zombie1_body02")
add("zombie_c", "zombie", "c_zom_zombie2_body01")
add("zombie_d", "zombie", "c_zom_zombie3_body01")
add("zombie_e", "zombie", "c_zom_zombie3_body02")
for h in "aknl":
    add(f"head_{h}", "head", f"c_zom_zombie_head_{h}")

add("mystery_box", "machine", "p6_anim_zm_magic_box")
add("mystery_box_fake", "machine", "p6_anim_zm_magic_box_fake")
add("pack_a_punch", "machine", "p6_anim_zm_buildable_pap")
add("pack_a_punch_on", "machine", "p6_anim_zm_buildable_pap_on")
add("power_switch_body", "machine", "p6_zm_buildable_pswitch_body")
add("power_switch_lever", "machine", "p6_zm_buildable_pswitch_lever")
add("power_switch_hand", "machine", "p6_zm_buildable_pswitch_hand")
for perk, stem in (("jugg", "jugg"), ("speed", "sleight"), ("doubletap", "doubletap2"), ("revive", "revive")):
    add(f"perk_{perk}", "machine", f"zombie_vending_{stem}")
    add(f"perk_{perk}_on", "machine", f"zombie_vending_{stem}_on")
for perk, stem in (("jugg", "jugg"), ("speed", "sleight"), ("doubletap", "doubletap"), ("revive", "revive")):
    add(f"bottle_{perk}", "perkbottle", f"t6_wpn_zmb_perk_bottle_{stem}_view", f"t6_wpn_zmb_perk_bottle_{stem}_world")

for p in ("engineer", "farmgirl", "oldman", "reporter"):
    add(f"player_{p}", "player", f"c_zom_player_{p}_fb")

json.dump(rows, open(os.path.join(SH, 'bo2_models.json'), 'w'), indent=1)
print(len(rows), 'bo2_models rows')
