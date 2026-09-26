/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed under the License is
 * distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */

package az.petek.faketarget.web

import az.petek.faketarget.model.User
import az.petek.faketarget.model.UserRole
import az.petek.faketarget.service.NotificationPanel
import kotlinx.html.ButtonType
import kotlinx.html.FlowContent
import kotlinx.html.FormMethod
import kotlinx.html.HTML
import kotlinx.html.UL
import kotlinx.html.a
import kotlinx.html.aside
import kotlinx.html.body
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.form
import kotlinx.html.h2
import kotlinx.html.head
import kotlinx.html.header
import kotlinx.html.li
import kotlinx.html.main
import kotlinx.html.meta
import kotlinx.html.nav
import kotlinx.html.script
import kotlinx.html.span
import kotlinx.html.style
import kotlinx.html.title
import kotlinx.html.ul
import kotlinx.html.unsafe

/** What the header of a logged-in page needs: who is logged in and their notifications. */
internal data class Chrome(
    val user: User,
    val companyName: String,
    val notifications: NotificationPanel,
)

/**
 * Page skeleton. With [chrome] (logged in) it renders the session block, the navigation and the live notification
 * panel on every page, as docs/TARGET_CONTRACT.md requires; without it a single centred column.
 */
internal fun HTML.page(
    title: String,
    chrome: Chrome?,
    pageScript: String? = null,
    content: FlowContent.() -> Unit,
) {
    attributes["lang"] = "az"
    head {
        meta(charset = "utf-8")
        meta(name = "viewport", content = "width=device-width, initial-scale=1")
        title("$title · KadroHR")
        style { unsafe { raw(Assets.STYLES) } }
    }
    body {
        header("top") {
            a(href = "/", classes = "brand") { +"KadroHR" }
            if (chrome != null) sessionHeader(chrome)
        }
        div(if (chrome != null) "layout" else "layout single") {
            main { content() }
            if (chrome != null) notificationPanel(chrome.notifications)
        }
        if (chrome != null) script { unsafe { raw(Assets.LIVE_NOTIFICATIONS) } }
        if (pageScript != null) script { unsafe { raw(pageScript) } }
    }
}

private fun FlowContent.sessionHeader(chrome: Chrome) {
    nav {
        attributes["aria-label"] = "Əsas menyu"
        ul {
            navLink("/", "nav-home", "Ana səhifə")
            navLink("/announcements", "nav-announcements", "Elanlar")
            navLink("/tickets", "nav-tickets", "Müraciətlər")
            if (chrome.user.role == UserRole.ADMIN) navLink("/company", "nav-company", "Şirkət")
        }
    }
    div("user") {
        span {
            testId("current-user-name")
            +chrome.user.name
        }
        span("role") {
            testId("current-user-role")
            attributes["title"] = chrome.user.role.label
            +chrome.user.role.key
        }
        span { +chrome.companyName }
        form(action = "/logout", method = FormMethod.post) {
            button(type = ButtonType.submit, classes = "secondary") {
                testId("logout")
                +"Çıxış"
            }
        }
    }
}

private fun UL.navLink(
    href: String,
    id: String,
    text: String,
) {
    li {
        a(href = href) {
            testId(id)
            +text
        }
    }
}

private fun FlowContent.notificationPanel(panel: NotificationPanel) {
    aside("notifications") {
        attributes["aria-label"] = "Bildirişlər"
        h2 {
            a(href = "/notifications") {
                testId("notification-bell")
                +"Bildirişlər "
                span("badge") {
                    testId("notification-count")
                    +panel.unread.toString()
                }
            }
        }
        ul {
            testId("notification-list")
            attributes["aria-live"] = "polite"
            panel.lastId?.let { attributes["data-last-id"] = it }
            if (panel.items.isEmpty()) li("empty") { +"Bildiriş yoxdur" }
            panel.items.forEach { notification ->
                li(if (notification.readAt == null) "unread" else "read") {
                    testId("notification-item")
                    dataId(notification.objectId)
                    attributes["data-notification-id"] = notification.id
                    a(href = notification.link) { +notification.text }
                }
            }
        }
    }
}
