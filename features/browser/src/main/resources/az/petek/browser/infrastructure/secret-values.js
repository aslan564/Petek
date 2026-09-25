/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

// Current non-empty values of the page's secret fields, so text taken from the page (e.g. an ARIA snapshot)
// can be redacted before it leaves the browser adapter.
() =>
  Array.from(document.querySelectorAll('input'))
    .filter(
      (input) =>
        (input.getAttribute('type') || '').toLowerCase() === 'password' ||
        /password/i.test(input.getAttribute('autocomplete') || ''),
    )
    .map((input) => input.value)
    .filter((value) => value && value.length > 0)
