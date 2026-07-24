// observe document if logout link is rendered and override it for SSO logout
const observer = new MutationObserver(() => {
  const logoutListItem = document.querySelector("li.account li.logout");
  if (logoutListItem) {
    observer.disconnect();

    const oldLogoutLink = logoutListItem.getElementsByTagName("a")[0];
    // create a clone so no listeners are attached anymore
    const logoutLink = oldLogoutLink.cloneNode(true);
    logoutListItem.replaceChild(logoutLink, oldLogoutLink);

    // find out the base url, i.e. the part until app-root
    const appRoot = document.querySelector("base").getAttribute("app-root");
    const idx = document.location.href.indexOf(appRoot);
    const baseUrl = document.location.href.substring(0, idx);
    
    logoutLink.href = baseUrl + appRoot + "/logout";
  }
});
observer.observe(document, { attributes: false, childList: true, characterData: false, subtree: true });

// on page load forward user from login page to dashboard
if (window.location.hash === '#/login') {
  window.location.hash = "";
}
