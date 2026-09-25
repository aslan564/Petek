/*
 * Pətək — multi-agent AI test platform. https://github.com/aslan564/Petek
 * Copyright (c) 2026 Kodcraft. Author: Aslan Aslanov. All rights reserved.
 *
 * Licensed under the Business Source License 1.1 (the "License"); you may not use this file except in
 * compliance with the License. See the LICENSE file in the repository root. Change Date: 2030-09-25;
 * Change License: Apache License, Version 2.0. The Licensed Work is provided "AS IS", without warranty.
 */

// Finds the <option> the caller means: exact label first, then exact value, then a case-insensitive label.
// Returns its index, or the labels that exist when nothing matches.
(select, wanted) => {
  if (!(select instanceof HTMLSelectElement)) return { error: 'not a <select> element' };
  const options = Array.from(select.options);
  const label = (option) => (option.label || option.text || '').replace(/\s+/g, ' ').trim();
  const needle = String(wanted).trim();
  let index = options.findIndex((option) => label(option) === needle);
  if (index < 0) index = options.findIndex((option) => option.value === String(wanted));
  if (index < 0) index = options.findIndex((option) => label(option).toLowerCase() === needle.toLowerCase());
  return index >= 0 ? { index } : { available: options.map(label) };
}
