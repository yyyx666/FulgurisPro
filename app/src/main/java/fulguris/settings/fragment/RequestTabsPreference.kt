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
import android.util.AttributeSet
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import fulguris.R

/**
 * The Request / Response switch at the top of a request's details.
 *
 * A segmented control rather than more rows in the list: those two are the same request seen from
 * either side, and showing one half at a time is what keeps the sheet readable on a phone.
 *
 * It extends `androidx.preference.Preference` and not `x.Preference`: the latter looks up
 * `android.R.id.title` and `android.R.id.summary` unconditionally when binding a view, and ours
 * holds neither.
 */
class RequestTabsPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : Preference(context, attrs) {

    enum class Pane { REQUEST, RESPONSE }

    /**
     * The half currently shown.
     */
    var pane: Pane = Pane.REQUEST
        private set

    /**
     * Called when the user switches halves. [select] does not go through here.
     */
    var onPaneSelected: ((Pane) -> Unit)? = null

    init {
        key = KEY
        layoutResource = R.layout.request_tabs_preference
        // The row itself does nothing, its two buttons do
        isSelectable = false
        isPersistent = false
        isIconSpaceReserved = false
    }

    /**
     * Move to [aPane] without telling [onPaneSelected]: our caller knows already, it is the one
     * moving us.
     */
    fun select(aPane: Pane) {
        pane = aPane
        notifyChanged()
    }

    /**
     * Move to [aPane] because the user asked for it.
     *
     * Two buttons can both be tapped in the middle of a rebind, so this is written to be safe to
     * call twice for the same pane: nothing happens the second time.
     */
    private fun switchTo(aPane: Pane) {
        if (aPane == pane) {
            return
        }

        pane = aPane
        notifyChanged()
        onPaneSelected?.invoke(aPane)
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)

        // Leave the taps to the two buttons: a clickable row would ripple under them for a
        // `performClick` that leads nowhere. What `DetailSwitchPreference` does with its switch.
        holder.itemView.isClickable = false
        holder.itemView.isFocusable = false

        // The row we are handed *is* our toggle group, and it is looked up as such. Looking it up
        // by the id the layout gives it would find nothing: `Preference` binds a row by setting its
        // id to `viewId`, which a preference built in code never has, so the root's own id is gone
        // by the time we run - while our two buttons, being children, keep theirs. The fallback
        // covers a root the framework one day wraps.
        val group = holder.itemView as? MaterialButtonToggleGroup
            ?: holder.itemView.findViewById(R.id.request_tabs_group)
            ?: return

        // Each button carries its own click listener rather than us listening to the group's
        // checked state: binding a row re-checks a button, and going through the group would have
        // our own binding report a change right back to us. The group is then left painting the
        // two buttons, driven by the check below.
        group.clearOnButtonCheckedListeners()

        bindPaneButton(holder.itemView.findViewById(R.id.request_tabs_request), Pane.REQUEST)
        bindPaneButton(holder.itemView.findViewById(R.id.request_tabs_response), Pane.RESPONSE)

        group.check(
            if (pane == Pane.RESPONSE) R.id.request_tabs_response else R.id.request_tabs_request
        )
    }

    /**
     * Make one half of the switch tappable, showing [aPane] when it is.
     */
    private fun bindPaneButton(button: MaterialButton?, aPane: Pane) {
        if (button == null) {
            return
        }

        // Checkable so the group paints the selected one, clickable so the tap reaches us at all
        button.isCheckable = true
        button.isClickable = true
        button.setOnClickListener { switchTo(aPane) }
    }

    companion object {
        /**
         * Key of that row in the screen we are added to.
         *
         * We are built in code rather than declared in `preference_page_request_details.xml`, so
         * that the fragment hands us [onPaneSelected] at construction and nothing has to look us up
         * afterwards. The key is only so we can be found if that ever becomes useful.
         */
        const val KEY = "request_tabs"
    }
}
