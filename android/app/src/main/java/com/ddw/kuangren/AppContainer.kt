package com.ddw.kuangren

import android.content.Context
import com.ddw.kuangren.data.Repository

class AppContainer(context: Context) {
    val repository: Repository = Repository(context.applicationContext)
}
