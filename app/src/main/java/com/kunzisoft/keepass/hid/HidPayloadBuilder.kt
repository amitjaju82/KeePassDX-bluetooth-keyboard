/*
 * Copyright 2019 Jeremy Jamet / Kunzisoft.
 *
 * This file is part of KeePassDX.
 *
 *  KeePassDX is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  KeePassDX is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with KeePassDX.  If not, see <http://www.gnu.org/licenses/>.
 *
 */
package com.kunzisoft.keepass.hid

import android.content.Context
import android.util.Log
import com.kunzisoft.keepass.settings.PreferencesUtil
import net.tjado.authorizer.HidKeyboardOutput
import java.io.ByteArrayOutputStream

/**
 * Turns text into the HID scancode stream for the host keyboard layout the user selected.
 */
object HidPayloadBuilder {

    private val TAG = HidPayloadBuilder::class.java.name

    /**
     * Either a complete scancode stream, or the count of characters the active layout cannot
     * produce. Nothing is ever half-typed: a password missing characters would be silently
     * wrong on the host, which is worse than an error the user can see.
     */
    sealed class Result {
        class Ready(val scancodes: ByteArray) : Result()
        class Unmappable(val count: Int) : Result()
    }

    fun layoutFor(context: Context): HidKeyboardOutput.Language {
        val stored = PreferencesUtil.getBluetoothHidLayout(context)
        return try {
            HidKeyboardOutput.Language.valueOf(stored)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "unknown keyboard layout '$stored', falling back to the default")
            HidKeyboardOutput.DEFAULT_LANGUAGE
        }
    }

    /**
     * Build the keystrokes for [text], optionally followed by Enter.
     *
     * @param text the value to type, as a char array so it is never materialised as an
     *             immutable String. Credential material - never log it.
     */
    fun build(context: Context, text: CharArray): Result {
        val keyboard = HidKeyboardOutput(layoutFor(context))
        val conversion = keyboard.convert(text)

        if (!conversion.isComplete) {
            conversion.wipe()
            return Result.Unmappable(conversion.unmappedCharacters.size)
        }

        if (!PreferencesUtil.isBluetoothHidAppendEnterEnabled(context)) {
            return Result.Ready(conversion.scancodes)
        }

        val out = ByteArrayOutputStream()
        out.write(conversion.scancodes)
        conversion.wipe()
        keyboard.appendKey(out, RETURN_KEY)
        return Result.Ready(out.toByteArray())
    }

    private const val RETURN_KEY = "return"
}
