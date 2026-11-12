const frame = document.createElement("iframe");
frame.className = "willow-frame";
frame.src = chrome.runtime.getURL("frame.html");
frame.hidden = true;

const button = document.createElement("button");
button.type = "button";
button.className = "willow-launch";
button.textContent = "W";
button.setAttribute("aria-label", "Open Willow");
button.addEventListener("click", () => {
  frame.hidden = false;
  frame.classList.remove("is-full");
  button.hidden = true;
});

window.addEventListener("message", (event) => {
  if (event.source !== frame.contentWindow) return;
  const type = event.data && event.data.type;
  if (type === "willow-hide") {
    frame.hidden = true;
    frame.classList.remove("is-full");
    button.hidden = false;
  } else if (type === "willow-full") {
    frame.hidden = false;
    frame.classList.add("is-full");
  } else if (type === "willow-panel") {
    frame.hidden = false;
    frame.classList.remove("is-full");
  }
});

document.documentElement.append(button, frame);
