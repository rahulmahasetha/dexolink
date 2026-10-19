// DexoLink Paste Assistant - Background Service Worker (Manifest V3)
// Provides a legitimate, user-initiated context menu action to paste into restricted web inputs

chrome.runtime.onInstalled.addListener(() => {
  chrome.contextMenus.create({
    id: "dexolink-paste-clean",
    title: "Paste via DexoLink Assistant",
    contexts: ["editable"]
  });
});

chrome.contextMenus.onClicked.addListener(async (info, tab) => {
  if (info.menuItemId === "dexolink-paste-clean" && tab?.id) {
    try {
      await chrome.scripting.executeScript({
        target: { tabId: tab.id },
        func: performLegitimatePaste
      });
    } catch (err) {
      console.error("[DexoLink Extension] Script execution failed:", err);
    }
  }
});

// Executed in the active page's context on explicit user click
async function performLegitimatePaste() {
  try {
    const text = await navigator.clipboard.readText();
    if (!text) {
      console.warn("[DexoLink Extension] Clipboard is empty");
      return;
    }

    const activeEl = document.activeElement;
    if (!activeEl) return;

    // Normalizes carriage returns and strips zero-width chars
    const cleanText = text.replace(/\r\n/g, '\n').replace(/\r/g, '\n').replace(/[\u200B-\u200D\uFEFF\u200E\u200F]/g, '');

    // Method 1: Standard supported DOM insertion (recognized by undo stack and rich editors)
    const success = document.execCommand('insertText', false, cleanText);

    if (!success) {
      // Method 2: Standard value insertion with full synthetic event dispatch
      if ('value' in activeEl) {
        const start = activeEl.selectionStart ?? activeEl.value.length;
        const end = activeEl.selectionEnd ?? activeEl.value.length;
        const currentVal = activeEl.value;
        activeEl.value = currentVal.substring(0, start) + cleanText + currentVal.substring(end);
        activeEl.selectionStart = activeEl.selectionEnd = start + cleanText.length;

        activeEl.dispatchEvent(new Event('input', { bubbles: true }));
        activeEl.dispatchEvent(new Event('change', { bubbles: true }));
      } else if (activeEl.isContentEditable) {
        const selection = window.getSelection();
        if (selection && selection.rangeCount > 0) {
          const range = selection.getRangeAt(0);
          range.deleteContents();
          range.insertNode(document.createTextNode(cleanText));
          activeEl.dispatchEvent(new Event('input', { bubbles: true }));
        }
      }
    }
  } catch (e) {
    console.error("[DexoLink Extension] Paste failed:", e);
  }
}
