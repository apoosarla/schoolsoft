/**
 * People module — students, guardians, staff, and the relationships between
 * them (guardian_student M:N, enrolment of students in sections, staff_role
 * RBAC grants). The single source of truth for "who is this person."
 */
@org.springframework.modulith.ApplicationModule(displayName = "People")
package com.schoolsoft.people;
