package com.projectswg.holocore.resources.support.data.server_info.loader

import com.projectswg.holocore.resources.support.objects.swg.weapon.WeaponType

class ForcePowersValidWeaponChecker : ValidWeaponChecker {
	override fun isValid(weaponType: WeaponType): Boolean {
		return true
	}
}