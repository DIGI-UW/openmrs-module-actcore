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

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.openmrs.Person;
import org.openmrs.PersonAddress;
import org.openmrs.PersonName;
import org.openmrs.Provider;
import org.openmrs.User;
import org.openmrs.api.context.Context;

/**
 * Keeps a clinic-limited administrator's PersonService and ProviderService changes to people who
 * have no user, or whose users it may manage (see {@link ClinicLimitedUserManagement}).
 */
public class ClinicLimitedPersonManagement implements MethodInterceptor {
	
	private static final List<String> CHANGES = Arrays.asList("savePerson", "voidPerson", "unvoidPerson", "purgePerson",
	    "savePersonName", "voidPersonName", "unvoidPersonName", "savePersonAddress", "voidPersonAddress",
	    "unvoidPersonAddress", "saveProvider", "retireProvider", "unretireProvider", "purgeProvider");
	
	@Override
	public Object invoke(MethodInvocation invocation) throws Throwable {
		Object[] args = invocation.getArguments();
		if (CHANGES.contains(invocation.getMethod().getName()) && args.length > 0 && ClinicUsers.isClinicLimited()) {
			User actor = Context.getAuthenticatedUser();
			Set<String> mine = ClinicUsers.clinicsOf(actor);
			for (Integer personId : people(args[0])) {
				for (Map.Entry<Integer, String> user : ClinicUsers.usersOf(personId).entrySet()) {
					if (!user.getKey().equals(actor.getUserId())) {
						ClinicLimitedUserManagement.checkManaged(user.getValue(), ClinicUsers.Stored.of(user.getKey()),
						    mine);
					}
				}
			}
		}
		return invocation.proceed();
	}
	
	private static Set<Integer> people(Object changed) {
		Set<Integer> ids = new HashSet<>();
		Person person = null;
		if (changed instanceof Person) {
			person = (Person) changed;
		} else if (changed instanceof PersonName) {
			person = ((PersonName) changed).getPerson();
		} else if (changed instanceof PersonAddress) {
			person = ((PersonAddress) changed).getPerson();
		} else if (changed instanceof Provider) {
			person = ((Provider) changed).getPerson();
			// Also the stored person: REST changes the loaded provider's person before saving it.
			ids.add(ClinicUsers.personOfProvider(((Provider) changed).getProviderId()));
		}
		if (person != null) {
			ids.add(person.getPersonId());
		}
		ids.remove(null);
		return ids;
	}
}
