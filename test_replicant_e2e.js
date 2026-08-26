const { chromium } = require('playwright');
const path = require('path');
const fs = require('fs');

const SCREENSHOTS_DIR = process.env.SCREENSHOTS_DIR || path.join(__dirname, 'test-results', 'replicant-e2e');
const BASE_URL = process.env.BASE_URL || 'http://localhost:8100';

if (!fs.existsSync(SCREENSHOTS_DIR)) {
  fs.mkdirSync(SCREENSHOTS_DIR, { recursive: true });
}

async function shot(page, name) {
  const p = path.join(SCREENSHOTS_DIR, `${name}.png`);
  await page.screenshot({ path: p, fullPage: false });
  console.log(`[screenshot] ${p}`);
}

(async () => {
  const browser = await chromium.launch({ headless: true });
  const page = await browser.newPage();

  // Capture console errors
  const errors = [];
  page.on('console', msg => {
    if (msg.type() === 'error') errors.push(msg.text());
  });
  page.on('pageerror', e => errors.push(`[pageerror] ${e.message}`));

  // --- LOGIN ---
  console.log('=== Step 1: Login ===');
  await page.goto(`${BASE_URL}/login`);
  await page.waitForSelector('#identifier', { timeout: 10000 });
  await page.fill('#identifier', 'alice@acme.com');
  await page.fill('#password', 'P@ssword123');
  await page.click('button[type="submit"]');
  await page.waitForTimeout(1500);
  console.log('After login URL:', page.url());

  // --- NAVIGATE TO /org-chart-replicant ---
  console.log('\n=== Step 2: Navigate to /org-chart-replicant ===');
  await page.goto(`${BASE_URL}/org-chart-replicant`);
  await page.waitForTimeout(2000); // wait for Replicant mount
  console.log('Page URL:', page.url());

  // Check what rendered
  const headingText = await page.textContent('h1').catch(() => '(no h1)');
  console.log('h1 text:', headingText);

  const pageText = await page.textContent('body');
  console.log('Body contains "Engineering":', pageText.includes('Engineering'));
  console.log('Body contains "Platform":', pageText.includes('Platform'));
  console.log('Body contains "Mobile":', pageText.includes('Mobile'));
  console.log('Body contains "Demo Co":', pageText.includes('Demo Co'));
  console.log('Body contains "Total Units":', pageText.includes('Total Units'));
  console.log('Body contains "Loading Replicant OrgChart":', pageText.includes('Loading Replicant OrgChart'));

  await shot(page, 'r01_initial_state');

  // --- STEP 5: Click Engineering card (toggle-collapse) ---
  console.log('\n=== Step 5: Click Engineering card (toggle collapse) ===');
  // Find Engineering card and click it
  const engCard = page.locator(':text("Engineering")').first();
  if (await engCard.isVisible()) {
    await engCard.click();
    await page.waitForTimeout(500);
    const afterText = await page.textContent('body');
    console.log('After click: Platform visible?', afterText.includes('Platform'));
    console.log('After click: Mobile visible?', afterText.includes('Mobile'));
    await shot(page, 'r02_eng_collapsed');

    // Click again to expand
    await engCard.click();
    await page.waitForTimeout(500);
    const reExpandText = await page.textContent('body');
    console.log('After re-click: Platform visible?', reExpandText.includes('Platform'));
    await shot(page, 'r03_eng_expanded_again');
  } else {
    console.log('Engineering card not found!');
  }

  // --- STEP 6: Collapse All ---
  console.log('\n=== Step 6: Collapse All ===');
  const collapseBtn = page.locator('button:text("Collapse All")');
  if (await collapseBtn.isVisible()) {
    await collapseBtn.click();
    await page.waitForTimeout(500);
    const colText = await page.textContent('body');
    console.log('After Collapse All - Platform visible?', colText.includes('Platform'));
    await shot(page, 'r04_collapse_all');
  } else {
    console.log('Collapse All button not found');
  }

  // --- STEP 7: Expand All ---
  console.log('\n=== Step 7: Expand All ===');
  const expandBtn = page.locator('button:text("Expand All")');
  if (await expandBtn.isVisible()) {
    await expandBtn.click();
    await page.waitForTimeout(500);
    const expText = await page.textContent('body');
    console.log('After Expand All - Platform visible?', expText.includes('Platform'));
    await shot(page, 'r05_expand_all');
  } else {
    console.log('Expand All button not found');
  }

  // --- STEP 8: Search ---
  console.log('\n=== Step 8: Type "platform" in search box ===');
  const searchInput = page.locator('input[placeholder*="Filter" i], input[type="text"]').first();
  if (await searchInput.isVisible()) {
    await searchInput.fill('platform');
    await page.waitForTimeout(500);
    // Check for ring-2 highlight
    const highlighted = await page.locator('.ring-2').count();
    console.log('Elements with ring-2 (highlight):', highlighted);
    await shot(page, 'r06_search_platform');
    // Clear search
    await searchInput.fill('');
    await page.waitForTimeout(300);
  } else {
    console.log('Search input not found');
  }

  // --- STEP 9: Click Platform leaf card ---
  console.log('\n=== Step 9: Click Platform leaf card ===');
  const platformCard = page.locator(':text("Platform")').first();
  if (await platformCard.isVisible()) {
    await platformCard.click();
    await page.waitForTimeout(1000);
    console.log('URL after Platform click:', page.url());
    await shot(page, 'r07_after_platform_click');
  } else {
    console.log('Platform card not found');
  }

  // --- Console errors summary ---
  console.log('\n=== Console Errors ===');
  if (errors.length === 0) {
    console.log('No JS console errors detected.');
  } else {
    errors.forEach(e => console.log('ERROR:', e));
  }

  await browser.close();
  console.log('\nDone.');
})();
