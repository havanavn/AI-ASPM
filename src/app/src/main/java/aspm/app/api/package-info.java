/**
 * The API surface. DOC-05, and ADR-036's seven annotation classes.
 *
 * <p>Lives in {@code app} because the API is the delivery layer over every module (DOC-02
 * section 6.1), and this is the only subproject permitted to see an {@code -impl}. It exports
 * nothing and nothing depends on it, so the permission widens no module's reach.
 */
package aspm.app.api;
