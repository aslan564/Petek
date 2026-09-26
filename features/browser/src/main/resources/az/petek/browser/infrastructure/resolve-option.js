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
