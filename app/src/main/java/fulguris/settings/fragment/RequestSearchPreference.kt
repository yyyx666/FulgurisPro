/*
 * The contents of this file are subject to the Common Public Attribution License Version 1.0.
 * (the "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at:
 * https://github.com/Slion/Fulguris/blob/main/LICENSE.CPAL-1.0.
 * The License is based on the Mozilla Public License Version 1.1, but Sections 14 and 15 have been
 * added to cover use of software over a computer network and provide for limited attribution for
 * the Original Developer. In addition, Exhibit A has been modified to be consistent with Exhibit B.
 *
 * Software distributed under the License is distributed on an "AS IS" basis, WITHOUT WARRANTY OF
 * ANY KIND, either express or implied. See the License for the specific language governing rights
 * and limitations under the License.
 *
 * The Original Code is Fulguris.
 *
 * The Original Developer is the Initial Developer.
 *
 * The Initial Developer of the Original Code is Stéphane Lenclud.
 *
 * All portions of the code written by Stéphane Lenclud are Copyright © 2020 Stéphane Lenclud.
 * All Rights Reserved.
 */

package fulguris.settings.fragment

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.util.AttributeSet
import android.widget.EditText
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import fulguris.R

/**
 * The URL filter field at the top of the requests list.
 *
 * A text field living in a row of its own rather than a dialog: the list behind it narrows on every
 * keystroke, which is what a search field is for.
 *
 * It extends `androidx.preference.Preference` and not `x.Preference`: the latter looks up
 * `android.R.id.title` and `android.R.id.summary` unconditionally when binding a view, and ours
 * holds neither.
 *
 * Whoever owns us keeps us out of whatever they rebuild - see `RequestsFragment`: the rows list
 * below is torn down and rebuilt on every keystroke, and a field rebuilt along with it would lose
 * the cursor, the selection and the keyboard along the way.
 */
class RequestSearchPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : Preference(context, attrs) {

    /**
     * What the user typed. Kept here so that a rebind can put the field back the way they left it.
     */
    var query: String = ""
        private set

    /**
     * Called on every keystroke, with the whole field.
     */
    var onQueryChanged: ((String) -> Unit)? = null

    init {
        key = KEY
        layoutResource = R.layout.request_search_preference
        // The field itself is what the user came for, and it takes the taps on its own
        isSelectable = false
        isPersistent = false
        isIconSpaceReserved = false
    }

    /**
     * Set [aQuery] from the outside, ours to say and not the user's to type.
     */
    fun setQuery(aQuery: String) {
        if (aQuery == query) {
            return
        }

        query = aQuery
        notifyChanged()
    }

    // Held rather than created per bind: the row can be bound again onto a field that already
    // carries a listener, and two of them would report every keystroke twice.
    private val watcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

        override fun afterTextChanged(s: Editable?) {
            val typed = s?.toString().orEmpty()
            if (typed == query) {
                // Our own `setText` coming back around, or a rebind handing us what we already have
                return
            }

            query = typed
            onQueryChanged?.invoke(typed)
        }
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)

        // The row we are handed *is* our field, and it is looked up as such. Looking it up by the
        // id the layout gives it would find nothing: `Preference` binds a row by setting its id to
        // `viewId`, which a preference built in code never has, so the root's own id is gone by the
        // time we run. The fallback covers a root the framework one day wraps.
        val field = holder.itemView as? EditText
            ?: holder.itemView.findViewById(R.id.request_search_field)
            ?: return

        // A row that cannot be selected comes out of binding with neither of these, and that is
        // what a row with a title to tap wants. Ours is the field itself: handed over that way it
        // takes no cursor and opens no keyboard, and typing at it goes to the page behind it. These
        // are the three the layout asks for, put back.
        field.isClickable = true
        field.isFocusable = true
        field.isFocusableInTouchMode = true

        field.removeTextChangedListener(watcher)
        field.addTextChangedListener(watcher)

        // Only when we differ, and that is the whole point of it: writing the text back would send
        // the cursor to the end of a field the user is in the middle of correcting.
        if (field.text?.toString() != query) {
            field.setText(query)
            field.setSelection(field.text.length)
        }
    }

    companion object {
        /**
         * Key of that row in the screen we are added to.
         *
         * We are built in code rather than declared in `preference_page_requests.xml`, so that the
         * fragment hands us [onQueryChanged] at construction and nothing has to look us up
         * afterwards. The key is only so we can be found if that ever becomes useful.
         */
        const val KEY = "request_url_filter"
    }
}
