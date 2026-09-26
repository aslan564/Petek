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

// Serializes the page like Playwright's `page.content()` (doctype + documentElement.outerHTML), but from a clone
// whose secret inputs carry no value attribute. Values typed by a user live in DOM properties, which outerHTML never
// serializes; server-rendered or script-set `value` attributes on password fields are blanked here.
() => {
  const clone = document.documentElement.cloneNode(true);
  for (const input of clone.querySelectorAll('input')) {
    const secret =
      (input.getAttribute('type') || '').toLowerCase() === 'password' ||
      /password/i.test(input.getAttribute('autocomplete') || '');
    if (secret && input.hasAttribute('value')) input.setAttribute('value', '');
  }
  const doctype = document.doctype ? new XMLSerializer().serializeToString(document.doctype) : '';
  return doctype + clone.outerHTML;
}
