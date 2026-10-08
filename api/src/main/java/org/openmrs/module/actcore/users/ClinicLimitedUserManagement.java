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
import org.openmrs.api.db.LoginCredential;
import org.openmrs.util.OpenmrsConstants;
import org.openmrs.util.PrivilegeConstants;
import org.springframework.stereotype.Component;

/**
 * Refuses, as the session flushes, a role change without Manage Roles, and a change to a user, its
 * person or provider beyond the actor's clinics and the roles it may give.
 */
@Component("actcore.clinicLimitedUserManagement")
public class ClinicLimitedUserManagement extends EmptyInterceptor {
	
	private static final long serialVersionUID = 1L;
	
	// Core stamps these on every entity it updates, so they change even when nothing else did.
	private static final Set<String> AUDIT = new HashSet<>(
	        Arrays.asList("changedBy", "dateChanged", "personChangedBy", "personDateChanged"));
	
	// Core writes these for whoever signs in, while the user signed in before is still the current one.
	private static final Set<String> SIGN_IN_PROPERTIES = new HashSet<>(
	        Arrays.asList(OpenmrsConstants.USER_PROPERTY_LOGIN_ATTEMPTS, OpenmrsConstants.USER_PROPERTY_LOCKOUT_TIMESTAMP,
	            "lastLoginTimestamp"));
	
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
	
	/** Whether the current user may change the user's clinics and roles: the rule the flush applies. */
	public static boolean mayEdit(User user) {
		if (!ClinicUsers.isClinicLimited()) {
			return true;
		}
		User actor = Context.getAuthenticatedUser();
		Set<String> mine = ClinicUsers.Stored.of(actor.getUserId()).clinics;
		return !actor.getUserId().equals(user.getUserId()) && !mine.isEmpty()
		        && unmanaged(ClinicUsers.Stored.of(user.getUserId()), mine) == null;
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
		} else if (owner instanceof User && userCollectionsChanged((User) owner)) {
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
		if (!Hibernate.isInitialized(user.getUserProperties())) {
			return false;
		}
		Map<String, String> now = new HashMap<>(user.getUserProperties());
		Map<String, String> before = new HashMap<>(stored.properties);
		now.keySet().removeAll(SIGN_IN_PROPERTIES);
		before.keySet().removeAll(SIGN_IN_PROPERTIES);
		return !now.equals(before);
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
	
	/** Refuses a change to a person one of whose users, other than the actor, it may not manage. */
	private static void checkPerson(User actor, Integer personId) {
		if (personId == null || !ClinicUsers.isClinicLimited()) {
			return;
		}
		Set<String> mine = ClinicUsers.Stored.of(actor.getUserId()).clinics;
		for (Integer userId : ClinicUsers.usersOf(personId)) {
			if (!userId.equals(actor.getUserId()) && !isCreatedById(userId)) {
				checkManaged(ClinicUsers.Stored.of(userId), mine);
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
		if (!ClinicUsers.isClinicLimited()) {
			checkRoles(roles, stored.roles, givable);
			return;
		}
		if (actor.getUserId().equals(userId)) {
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
			checkManaged(stored, mine);
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
	
	private static void checkManaged(ClinicUsers.Stored stored, Set<String> mine) {
		String refusal = unmanaged(stored, mine);
		if (refusal != null) {
			throw refusal(refusal);
		}
	}
	
	/** Why a stored user who shares none of {@code mine} or who manages users is refused, or null. */
	private static String unmanaged(ClinicUsers.Stored stored, Set<String> mine) {
		if (Collections.disjoint(stored.clinics, mine)) {
			return stored.name + " is not at your clinics";
		}
		if (ClinicUsers.managesUsers(stored.roles)) {
			return stored.name + " administers users, which only an instance administrator can change";
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
