/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

// Is this element a secret field (a password input, or any input the page marks as a password by autocomplete)?
// Text the adapter types into one is remembered and masked everywhere afterwards, even once the page shows it.
(element) =>
  element instanceof HTMLInputElement &&
  ((element.getAttribute('type') || '').toLowerCase() === 'password' ||
    /password/i.test(element.getAttribute('autocomplete') || ''))
