package com.mahmoud.gymledger

import android.app.Application

class GymApp : Application() {
    val database by lazy { GymDatabase.open(this) }
    val repository by lazy { GymRepository(database) }
    val wifi by lazy { WifiGate(this) }
}
