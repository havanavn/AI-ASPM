/**
 * Published contract of the identity module [generic], DOC-03 section 5.1 context 11.
 *
 * <p>Supplies principal existence only (ADR-041). Authorization consumes PrincipalId from the shared kernel rather than depending on this module, because CON-PLT-011 forbids a kernel dependency on a domain module.
 *
 * <p>Shell only at this stage. The aggregate, its invariants and its schema are
 * implemented in the prompt that owns this module.
 */
package aspm.module.identity.contract;
