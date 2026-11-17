// Mobile page ownership boundary for consumers outside the navigation face.
export function isLiveMobilePage(key) {
  return document.body.classList.contains('mobile-layer')
    && !!document.querySelector(`#mobile-pages .mobile-page[data-page="${key}"].live`);
}
