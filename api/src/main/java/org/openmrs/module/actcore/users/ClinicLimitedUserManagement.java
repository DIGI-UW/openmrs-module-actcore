/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.actcore.users;

import java.io.Serializable;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import org.hibernate.EmptyInterceptor;
import org.hibernate.Hibernate;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;
import org.hibernate.collection.spi.PersistentCollection;
import org.hibernate.engine.spi.SessionImplementor;
import org.hibernate.persister.entity.EntityPersister;
import org.hibernate.proxy.HibernateProxy;
import org.hibernate.type.Type;
import org.openmrs.OpenmrsObject;
import org.openmrs.Person;
import org.openmrs.PersonAddress;
import org.openmrs.PersonAttribute;
import org.openmrs.PersonName;
import org.openmrs.Privilege;
import org.openmrs.Provider;
import org.openmrs.Role;
import org.openmrs.User;
import org.openmrs.api.APIAuthenticationException;
import org.openmrs.api.context.Context;
import org.openmrs.api.context.Daemon;
import org.openmrs.api.context.UserContext;
import org.openmrs.api.db.LoginCredential;
import org.openmrs.util.OpenmrsConstants;
import org.openmrs.util.PrivilegeConstants;
import org.springframework.stereotype.Component;

/**
 * Refuses at flush a role change without Manage Roles, a change to another user who outranks the
 * actor, and a change to a user, its person or provider beyond the actor's clinics and roles.
 */
@Component("actcore.clinicLimitedUserManagement")
public class ClinicLimitedUserManagement extends EmptyInterceptor {
	
	private static final long serialVersionUID = 1L;
	
	// Core stamps these on every entity it updates, so they change even when nothing else did.
	private static final Set<String> AUDIT = new HashSet<>(
	        Arrays.asList("changedBy", "dateChanged", "personChangedBy", "personDateChanged"));
	
	// Core writes these on the user signing in while the session is still the previous user's.
	private static final Set<String> SIGN_IN = new HashSet<>(Arrays.asList(OpenmrsConstants.USER_PROPERTY_LOGIN_ATTEMPTS,
	    OpenmrsConstants.USER_PROPERTY_LOCKOUT_TIMESTAMP, "lastLoginTimestamp"));
	
	// Users a clinic-limited user created in this transaction, whose clinics are not stored yet.
	private static final ThreadLocal<Map<User, Integer>> CREATED = ThreadLocal.withInitial(IdentityHashMap::new);
	
	@Override
	public boolean onSave(Object entity, Serializable id, Object[] state, String[] names, Type[] types) {
		User actor = actor();
		if (actor != null && entity instanceof User) {
			User user = (User) entity;
			checkUser(actor, user, true);
			checkPerson(actor, user.getPerson());
			if (ClinicUsers.isClinicLimited()) {
				CREATED.get().put(user, actor.getUserId());
			}
		} else if (actor != null) {
			checkChange(actor, entity, null);
		}
		return false;
	}
	
	@Override
	public boolean onFlushDirty(Object entity, Serializable id, Object[] current, Object[] previous, String[] names,
	        Type[] types) {
		User actor = actor();
		if (actor == null || !(entity instanceof Role || entity instanceof User || entity instanceof LoginCredential
		        || isPersonData(entity)) || !changed(entity, id, current, previous, names, types)) {
			return false;
		}
		if (entity instanceof User) {
			User user = (User) entity;
			if (ClinicUsers.isClinicLimited()
			        && !user.getPerson().getPersonId().equals(ClinicUsers.Stored.of(user.getUserId()).personId)) {
				throw refusal("You cannot move a user to another person");
			}
			checkUser(actor, user, false);
		} else if (entity instanceof LoginCredential) {
			checkStoredUser(actor, ((LoginCredential) entity).getUserId());
		} else {
			int person = Arrays.asList(names).indexOf("person");
			Object[] before = previous != null ? previous : snapshot(entity, id);
			checkChange(actor, entity, person >= 0 && before != null ? idOf(before[person]) : null);
		}
		return false;
	}
	
	@Override
	public void onDelete(Object entity, Serializable id, Object[] state, String[] names, Type[] types) {
		User actor = actor();
		if (actor != null && entity instanceof User) {
			checkStoredUser(actor, ((User) entity).getUserId());
		} else if (actor != null) {
			checkChange(actor, entity, null);
		}
	}
	
	@Override
	public void onCollectionRecreate(Object collection, Serializable key) {
		collectionChanged(collection);
	}
	
	@Override
	public void onCollectionRemove(Object collection, Serializable key) {
		collectionChanged(collection);
	}
	
	@Override
	public void onCollectionUpdate(Object collection, Serializable key) {
		collectionChanged(collection);
	}
	
	@Override
	public void afterTransactionCompletion(Transaction transaction) {
		CREATED.remove();
	}
	
	/** Which users the current user may change the clinics and roles of: the rule the flush applies. */
	public static Predicate<User> editable() {
		User actor = Context.getAuthenticatedUser();
		if (actor.isSuperUser()) {
			return user -> true;
		}
		ClinicUsers.Roles roles = ClinicUsers.Roles.stored();
		if (!ClinicUsers.isClinicLimited()) {
			return user -> unmanaged(actor, roles, ClinicUsers.Stored.of(user.getUserId()), null) == null;
		}
		Set<String> mine = ClinicUsers.Stored.of(actor.getUserId()).clinics;
		return user -> !actor.getUserId().equals(user.getUserId()) && !mine.isEmpty()
		        && unmanaged(actor, roles, ClinicUsers.Stored.of(user.getUserId()), mine) == null;
	}
	
	/** The signed-in user whose changes are judged: none for a daemon, a superuser or no one. */
	private static User actor() {
		if (Daemon.isDaemonThread() || !Context.isSessionOpen()) {
			return null;
		}
		User actor = Context.getAuthenticatedUser();
		return actor == null || actor.isSuperUser() ? null : actor;
	}
	
	private static void collectionChanged(Object collection) {
		User actor = actor();
		Object owner = collection instanceof PersistentCollection ? ((PersistentCollection) collection).getOwner() : null;
		if (actor == null) {
			return;
		}
		if (owner instanceof Role && roleChanged((Role) owner)) {
			checkRoleChange(actor, (Role) owner);
		} else if (owner instanceof User && userCollectionsChanged((User) owner) && !isSignInBookkeeping((User) owner)) {
			checkUser(actor, (User) owner, false);
		}
	}
	
	private static boolean roleChanged(Role role) {
		String name = role.getRole();
		return !ClinicUsers.roleNames(role.getInheritedRoles())
		        .equals(stored("select parent_role from role_role where child_role = ?", name))
		        || !ClinicUsers.roleNames(role.getChildRoles())
		                .equals(stored("select child_role from role_role where parent_role = ?", name))
		        || !privilegeNames(role.getPrivileges())
		                .equals(stored("select privilege from role_privilege where role = ?", name));
	}
	
	private static boolean userCollectionsChanged(User user) {
		if (isCreated(user)) {
			return true;
		}
		ClinicUsers.Stored stored = ClinicUsers.Stored.of(user.getUserId());
		if (!ClinicUsers.roleNames(user.getRoles()).equals(stored.roles)) {
			return true;
		}
		return Hibernate.isInitialized(user.getUserProperties())
		        && !new HashMap<>(user.getUserProperties()).equals(stored.properties);
	}
	
	/** Whether core is signing someone in and changed only the user's sign-in counters and times. */
	private static boolean isSignInBookkeeping(User user) {
		if (isCreated(user) || !Hibernate.isInitialized(user.getUserProperties()) || !isSigningIn()) {
			return false;
		}
		ClinicUsers.Stored stored = ClinicUsers.Stored.of(user.getUserId());
		return ClinicUsers.roleNames(user.getRoles()).equals(stored.roles)
		        && withoutSignIn(user.getUserProperties()).equals(withoutSignIn(stored.properties));
	}
	
	/**
	 * Whether core's sign-in is flushing: the counters alone, sent through REST, must still be judged.
	 */
	private static boolean isSigningIn() {
		for (StackTraceElement frame : new Throwable().getStackTrace()) {
			if (UserContext.class.getName().equals(frame.getClassName()) && "authenticate".equals(frame.getMethodName())) {
				return true;
			}
		}
		return false;
	}
	
	private static Map<String, String> withoutSignIn(Map<String, String> properties) {
		Map<String, String> rest = new HashMap<>(properties);
		rest.keySet().removeAll(SIGN_IN);
		return rest;
	}
	
	private static void checkChange(User actor, Object entity, Integer personBefore) {
		if (entity instanceof Role) {
			checkRoleChange(actor, (Role) entity);
			return;
		}
		Person person = personOf(entity);
		if (person != null) {
			checkPerson(actor, person.getPersonId());
		}
		if (personBefore != null) {
			checkPerson(actor, personBefore);
		}
	}
	
	private static void checkRoleChange(User actor, Role role) {
		if (!actor.hasPrivilege(PrivilegeConstants.MANAGE_ROLES)) {
			throw refusal("Changing the role " + role.getRole() + " needs " + PrivilegeConstants.MANAGE_ROLES);
		}
	}
	
	private static void checkPerson(User actor, Person person) {
		if (person != null) {
			checkPerson(actor, person.getPersonId());
		}
	}
	
	/**
	 * Refuses an administrator, a holder of Add Users or Edit Users, a change to a person one of whose
	 * users, other than itself, it may not manage. Others edit people as OpenMRS lets them.
	 */
	private static void checkPerson(User actor, Integer personId) {
		if (personId == null || !(actor.hasPrivilege(PrivilegeConstants.ADD_USERS)
		        || actor.hasPrivilege(PrivilegeConstants.EDIT_USERS))) {
			return;
		}
		Set<String> mine = ClinicUsers.isClinicLimited() ? ClinicUsers.Stored.of(actor.getUserId()).clinics : null;
		for (Integer userId : ClinicUsers.usersOf(personId)) {
			if (!userId.equals(actor.getUserId()) && !isCreatedById(userId)) {
				checkManaged(actor, ClinicUsers.Stored.of(userId), mine);
			}
		}
	}
	
	private static void checkStoredUser(User actor, Integer userId) {
		if (!isCreatedById(userId)) {
			ClinicUsers.Stored stored = ClinicUsers.Stored.of(userId);
			check(actor, userId, false, stored, stored.clinics, stored.roles);
		}
	}
	
	private static void checkUser(User actor, User user, boolean isNew) {
		boolean created = isNew || isCreated(user);
		ClinicUsers.Stored stored = created ? ClinicUsers.Stored.NONE : ClinicUsers.Stored.of(user.getUserId());
		Set<String> clinics = Hibernate.isInitialized(user.getUserProperties()) ? ClinicUsers.clinicsOf(user)
		        : stored.clinics;
		check(actor, user.getUserId(), created, stored, clinics, ClinicUsers.roleNames(user.getRoles()));
	}
	
	private static void check(User actor, Integer userId, boolean created, ClinicUsers.Stored stored, Set<String> clinics,
	        Set<String> roles) {
		Set<String> givable = ClinicUsers.givableRoles();
		boolean self = actor.getUserId().equals(userId);
		if (!ClinicUsers.isClinicLimited()) {
			if (!created && !self) {
				checkManaged(actor, stored, null);
			}
			checkRoles(roles, stored.roles, givable);
			return;
		}
		if (self) {
			// Saving one's own preferences, such as the login location, stays open.
			if (!clinics.equals(stored.clinics) || !roles.equals(stored.roles)) {
				throw refusal("You cannot change your own clinics or roles");
			}
			return;
		}
		Set<String> mine = ClinicUsers.Stored.of(actor.getUserId()).clinics;
		if (mine.isEmpty()) {
			throw refusal("You have no clinics, so you cannot manage users");
		}
		if (!created) {
			checkManaged(actor, stored, mine);
		}
		if (clinics.isEmpty()) {
			throw refusal("Give the user at least one of your clinics");
		}
		if (!mine.containsAll(changed(clinics, stored.clinics))) {
			throw refusal("You can add or remove only your own clinics");
		}
		checkRoles(roles, stored.roles, givable);
	}
	
	private static void checkRoles(Set<String> roles, Set<String> before, Set<String> givable) {
		for (String role : changed(roles, before)) {
			if (!givable.contains(role)) {
				throw refusal("You cannot give or remove the role " + role);
			}
		}
	}
	
	private static void checkManaged(User actor, ClinicUsers.Stored stored, Set<String> mine) {
		String refusal = unmanaged(actor, ClinicUsers.Roles.stored(), stored, mine);
		if (refusal != null) {
			throw refusal(refusal);
		}
	}
	
	/**
	 * Why the actor may not change a stored user, or null: one whose roles outrank its own, and, with
	 * {@code mine} (null when not clinic-limited), one sharing none of them or who manages users.
	 */
	private static String unmanaged(User actor, ClinicUsers.Roles roles, ClinicUsers.Stored stored, Set<String> mine) {
		if (mine != null && Collections.disjoint(stored.clinics, mine)) {
			return stored.name + " is not at your clinics";
		}
		if (mine != null && roles.manageUsers(stored.roles)) {
			return stored.name + " administers users, which only an instance administrator can change";
		}
		if (roles.outrank(actor, stored.roles)) {
			return stored.name + " holds a role or privilege you lack";
		}
		return null;
	}
	
	private static boolean isCreated(User user) {
		User actor = Context.getAuthenticatedUser();
		return actor != null && actor.getUserId().equals(CREATED.get().get(user));
	}
	
	private static boolean isCreatedById(Integer userId) {
		for (User user : CREATED.get().keySet()) {
			if (userId.equals(user.getUserId()) && isCreated(user)) {
				return true;
			}
		}
		return false;
	}
	
	private static boolean isPersonData(Object entity) {
		return entity instanceof Person || entity instanceof PersonName || entity instanceof PersonAddress
		        || entity instanceof PersonAttribute || entity instanceof Provider;
	}
	
	private static Person personOf(Object entity) {
		if (entity instanceof Person) {
			return (Person) entity;
		}
		if (entity instanceof PersonName) {
			return ((PersonName) entity).getPerson();
		}
		if (entity instanceof PersonAddress) {
			return ((PersonAddress) entity).getPerson();
		}
		if (entity instanceof PersonAttribute) {
			return ((PersonAttribute) entity).getPerson();
		}
		if (entity instanceof Provider) {
			return ((Provider) entity).getPerson();
		}
		return null;
	}
	
	/** The row as the database holds it, for an entity the session reattached without a snapshot. */
	private static Object[] snapshot(Object entity, Serializable id) {
		SessionImplementor session = sessions().getCurrentSession().unwrap(SessionImplementor.class);
		return session.getPersistenceContext().getDatabaseSnapshot(id, session.getEntityPersister(null, entity));
	}
	
	/** Whether a property other than the audit fields and the collections changed. */
	private static boolean changed(Object entity, Serializable id, Object[] current, Object[] previous, String[] names,
	        Type[] types) {
		SessionImplementor session = sessions().getCurrentSession().unwrap(SessionImplementor.class);
		EntityPersister persister = session.getEntityPersister(null, entity);
		Object[] snapshot = previous == null ? snapshot(entity, id) : null;
		if (previous == null && snapshot == null) {
			return true;
		}
		int[] dirty = previous != null ? persister.findDirty(current, previous, entity, session)
		        : persister.findModified(snapshot, current, entity, session);
		for (int i : dirty != null ? dirty : new int[0]) {
			if (!AUDIT.contains(names[i]) && !types[i].isCollectionType()) {
				return true;
			}
		}
		return false;
	}
	
	private static Integer idOf(Object value) {
		if (value instanceof HibernateProxy) {
			return (Integer) ((HibernateProxy) value).getHibernateLazyInitializer().getIdentifier();
		}
		if (value instanceof OpenmrsObject) {
			return ((OpenmrsObject) value).getId();
		}
		return (Integer) value;
	}
	
	private static Set<String> stored(String sql, String role) {
		return new HashSet<>(ClinicUsers.column(sql, role));
	}
	
	private static Set<String> privilegeNames(Collection<Privilege> privileges) {
		Set<String> names = new HashSet<>();
		if (privileges != null) {
			for (Privilege privilege : privileges) {
				names.add(privilege.getPrivilege());
			}
		}
		return names;
	}
	
	private static Set<String> changed(Set<String> after, Set<String> before) {
		Set<String> changed = new HashSet<>(after);
		changed.addAll(before);
		Set<String> unchanged = new HashSet<>(after);
		unchanged.retainAll(before);
		changed.removeAll(unchanged);
		return changed;
	}
	
	private static SessionFactory sessions() {
		return Context.getRegisteredComponent("sessionFactory", SessionFactory.class);
	}
	
	private static APIAuthenticationException refusal(String message) {
		return new APIAuthenticationException(message);
	}
}
