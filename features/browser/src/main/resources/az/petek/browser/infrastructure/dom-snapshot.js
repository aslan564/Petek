/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
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
