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

// How fast the current page became usable, as the browser itself timed it: Navigation Timing (first byte, DOM ready,
// load) and the largest-contentful-paint and layout-shift entries kept in its buffer. Milliseconds from the start of
// the navigation; null where the browser reported nothing. Reads only; the observers are gone before it returns.
async () => {
  const ms = (value) => (typeof value === 'number' && value > 0 ? Math.round(value) : null);
  const buffered = (type) =>
    new Promise((resolve) => {
      try {
        const entries = [];
        const observer = new PerformanceObserver((list) => entries.push(...list.getEntries()));
        observer.observe({ type, buffered: true });
        setTimeout(() => {
          observer.disconnect();
          resolve(entries);
        }, 50);
      } catch (e) {
        resolve(null);
      }
    });
  const navigation = performance.getEntriesByType('navigation')[0];
  const [paints, shifts] = await Promise.all([buffered('largest-contentful-paint'), buffered('layout-shift')]);
  const largest = paints && paints.length ? paints[paints.length - 1] : null;
  const shift = shifts
    ? Math.round(shifts.filter((entry) => !entry.hadRecentInput).reduce((sum, entry) => sum + entry.value, 0) * 1000) / 1000
    : null;
  return {
    ttfb: navigation ? ms(navigation.responseStart) : null,
    domContentLoaded: navigation ? ms(navigation.domContentLoadedEventEnd) : null,
    load: navigation ? ms(navigation.loadEventEnd) : null,
    largestPaint: largest ? ms(largest.renderTime || largest.loadTime || largest.startTime) : null,
    layoutShift: shift,
  };
};
