package com.dakingx.app.dkcamerax.config

import android.content.Context

fun Context.getFileProviderAuthority() = "${packageName}.FILE_PROVIDER"
