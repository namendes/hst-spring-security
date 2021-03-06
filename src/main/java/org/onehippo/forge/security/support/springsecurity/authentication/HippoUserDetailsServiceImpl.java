/*
 *  Copyright 2011 Hippo.
 * 
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 * 
 *       http://www.apache.org/licenses/LICENSE-2.0
 * 
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.onehippo.forge.security.support.springsecurity.authentication;

import java.math.BigDecimal;
import java.text.MessageFormat;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import javax.jcr.Credentials;
import javax.jcr.LoginException;
import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.Property;
import javax.jcr.PropertyIterator;
import javax.jcr.PropertyType;
import javax.jcr.Repository;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.Value;
import javax.jcr.query.Query;
import javax.jcr.query.QueryResult;

import org.hippoecm.hst.site.HstServices;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

/**
 * Hippo Repository based UserDetailsService implementation.
 *
 * @see org.springframework.security.core.userdetails.UserDetailsService
 */
public class HippoUserDetailsServiceImpl implements HippoUserDetailsService {

    static final Logger log = LoggerFactory.getLogger(HippoUserDetailsServiceImpl.class);

    private static final String DEFAULT_USER_QUERY = "//hippo:configuration/hippo:users/{0}";

    private static final String DEFAULT_GROUPS_OF_USER_QUERY = "//element(*, hipposys:group)[(@hipposys:members = ''{0}'' or @hipposys:members = ''*'') and @hipposys:securityprovider = ''internal'']";

    private static final String DEFAULT_ROLES_OF_USER_AND_GROUP_QUERY = "//hippo:configuration/hippo:domains/{0}/element(*, hipposys:authrole)[ @hipposys:users = ''{1}'' {2}]";

    private static final Set<String> userPropsToExclude = Collections
            .unmodifiableSet(new HashSet<>(Arrays.asList("hipposys:active", "hipposys:password",
                    "hipposys:passwordlastmodified", "hipposys:securityprovider")));

    private Repository systemRepository;

    private Credentials systemCreds;

    private String queryLanguage = Query.XPATH;

    private String userQuery = DEFAULT_USER_QUERY;

    private String groupsOfUserQuery = DEFAULT_GROUPS_OF_USER_QUERY;

    private String roleDomainName = "everywhere";

    private String rolesOfUserAndGroupQuery = DEFAULT_ROLES_OF_USER_AND_GROUP_QUERY;

    private String defaultRoleName;

    private String rolePrefix = "ROLE_";

    public HippoUserDetailsServiceImpl() {
    }

    public void setSystemRepository(Repository systemRepository) {
        this.systemRepository = systemRepository;
    }

    public Repository getSystemRepository() {
        if (systemRepository == null) {
            systemRepository = HstServices.getComponentManager().getComponent(Repository.class.getName());
        }

        return systemRepository;
    }

    public void setSystemCredentials(Credentials systemCreds) {
        this.systemCreds = systemCreds;
    }

    public Credentials getSystemCredentials() {
        if (systemCreds == null) {
            systemCreds = HstServices.getComponentManager().getComponent(
                    Credentials.class.getName() + ".hstconfigreader");
        }

        return systemCreds;
    }

    public void setQueryLanguage(String queryLanguage) {
        this.queryLanguage = queryLanguage;
    }

    public String getQueryLanguage() {
        return queryLanguage;
    }

    public String getUserQuery() {
        return userQuery;
    }

    public void setUserQuery(String userQuery) {
        this.userQuery = userQuery;
    }

    public void setGroupsOfUserQuery(String groupsOfUserQuery) {
        this.groupsOfUserQuery = groupsOfUserQuery;
    }

    public String getGroupsOfUserQuery() {
        return groupsOfUserQuery;
    }

    public void setRoleDomainName(String roleDomainName) {
        this.roleDomainName = roleDomainName;
    }

    public String getRoleDomainName() {
        return roleDomainName;
    }

    public void setRolesOfUserAndGroupQuery(String rolesOfUserAndGroupQuery) {
        this.rolesOfUserAndGroupQuery = rolesOfUserAndGroupQuery;
    }

    public String getRolesOfUserAndGroupQuery() {
        return rolesOfUserAndGroupQuery;
    }

    public void setDefaultRoleName(String defaultRoleName) {
        this.defaultRoleName = defaultRoleName;
    }

    public String getDefaultRoleName() {
        return defaultRoleName;
    }

    public String getRolePrefix() {
        return rolePrefix;
    }

    public void setRolePrefix(String rolePrefix) {
        this.rolePrefix = rolePrefix;
    }

    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        return loadUserByUsernameAndPassword(username, null);
    }

    public UserDetails loadUserByUsernameAndPassword(String username, String password)
            throws UsernameNotFoundException {
        User user = null;
        Session session = null;

        try {
            if (getSystemCredentials() != null) {
                session = getSystemRepository().login(getSystemCredentials());
            } else {
                session = getSystemRepository().login();
            }

            String statement = MessageFormat.format(getUserQuery(), username);

            if (log.isDebugEnabled()) {
                log.debug("Searching user with query: " + statement);
            }

            Query q = session.getWorkspace().getQueryManager().createQuery(statement, getQueryLanguage());
            QueryResult result = q.execute();
            NodeIterator nodeIt = result.getNodes();
            Node userNode = (nodeIt.hasNext() ? userNode = nodeIt.nextNode() : null);

            if (userNode != null) {
                String passwordProp = userNode.getProperty("hipposys:password").getString();
                boolean enabled = userNode.getProperty("hipposys:active").getBoolean();
                boolean accountNonExpired = true;
                boolean credentialsNonExpired = true;
                boolean accountNonLocked = true;
                Collection<? extends GrantedAuthority> authorities = getGrantedAuthoritiesOfUser(username);

                user = new HippoUser(username, password != null ? password : passwordProp, enabled, accountNonExpired,
                        credentialsNonExpired, accountNonLocked, authorities, getUserProperties(userNode));
            }
        } catch (RepositoryException e) {
            log.warn("Failed to load user.", e);
        } finally {
            if (session != null) {
                try {
                    session.logout();
                } catch (Exception ignore) {
                }
            }
        }

        return user;
    }

    protected Collection<? extends GrantedAuthority> getGrantedAuthoritiesOfUser(String username)
            throws LoginException, RepositoryException {
        Set<GrantedAuthority> authorities = new HashSet<GrantedAuthority>();
        Session session = null;

        try {
            if (getSystemCredentials() != null) {
                session = getSystemRepository().login(getSystemCredentials());
            } else {
                session = getSystemRepository().login();
            }

            String statement = MessageFormat.format(getGroupsOfUserQuery(), username);

            if (log.isDebugEnabled()) {
                log.debug("Searching groups of user with query: " + statement);
            }

            Query q = session.getWorkspace().getQueryManager().createQuery(statement, getQueryLanguage());
            QueryResult result = q.execute();
            NodeIterator nodeIt = result.getNodes();

            StringBuilder groupsConstraintsBuilder = new StringBuilder(100);

            while (nodeIt.hasNext()) {
                String groupName = nodeIt.nextNode().getName();
                groupsConstraintsBuilder.append("or @hipposys:groups = '").append(groupName).append("' ");
            }

            statement = MessageFormat.format(getRolesOfUserAndGroupQuery(), getRoleDomainName(), username,
                    groupsConstraintsBuilder.toString());

            q = session.getWorkspace().getQueryManager().createQuery(statement, getQueryLanguage());
            result = q.execute();
            nodeIt = result.getNodes();

            boolean defaultRoleAdded = false;

            while (nodeIt.hasNext()) {
                String roleName = nodeIt.nextNode().getProperty("hipposys:role").getString();
                String prefixedRoleName = (rolePrefix != null ? rolePrefix + roleName : roleName);
                authorities.add(new SimpleGrantedAuthority(prefixedRoleName));

                if (defaultRoleName != null && !defaultRoleAdded && roleName.equals(defaultRoleName)) {
                    defaultRoleAdded = true;
                }
            }

            if (defaultRoleName != null && !defaultRoleAdded) {
                String prefixedRoleName = (rolePrefix != null ? rolePrefix + defaultRoleName : defaultRoleName);
                authorities.add(new SimpleGrantedAuthority(prefixedRoleName));
            }
        } finally {
            if (session != null) {
                try {
                    session.logout();
                } catch (Exception ignore) {
                }
            }
        }

        return authorities;
    }

    protected Map<String, Object> getUserProperties(final Node userNode) throws RepositoryException {
        Map<String, Object> userProps = new HashMap<>();

        Property prop;
        String propName;

        Value[] valArr;

        String[] strValArr;
        long[] longValArr;
        BigDecimal[] decValArr;
        boolean[] boolValArr;
        Calendar[] dateValArr;

        for (PropertyIterator propIt = userNode.getProperties(); propIt.hasNext(); ) {
            prop = propIt.nextProperty();
            propName = prop.getName();

            if (userPropsToExclude.contains(propName)) {
                continue;
            }

            if (prop.isMultiple()) {
                switch (prop.getType()) {
                case PropertyType.STRING:
                    valArr = prop.getValues();
                    strValArr = new String[valArr.length];
                    for (int i = 0; i < valArr.length; i++) {
                        strValArr[i] = valArr[i].getString();
                    }
                    userProps.put(propName, strValArr);
                    break;
                case PropertyType.LONG:
                    valArr = prop.getValues();
                    longValArr = new long[valArr.length];
                    for (int i = 0; i < valArr.length; i++) {
                        longValArr[i] = valArr[i].getLong();
                    }
                    userProps.put(propName, longValArr);
                    break;
                case PropertyType.DECIMAL:
                    valArr = prop.getValues();
                    decValArr = new BigDecimal[valArr.length];
                    for (int i = 0; i < valArr.length; i++) {
                        decValArr[i] = valArr[i].getDecimal();
                    }
                    userProps.put(propName, decValArr);
                    break;
                case PropertyType.BOOLEAN:
                    valArr = prop.getValues();
                    boolValArr = new boolean[valArr.length];
                    for (int i = 0; i < valArr.length; i++) {
                        boolValArr[i] = valArr[i].getBoolean();
                    }
                    userProps.put(propName, boolValArr);
                    break;
                case PropertyType.DATE:
                    valArr = prop.getValues();
                    dateValArr = new Calendar[valArr.length];
                    for (int i = 0; i < valArr.length; i++) {
                        dateValArr[i] = valArr[i].getDate();
                    }
                    userProps.put(propName, dateValArr);
                    break;
                }
            } else {
                switch (prop.getType()) {
                case PropertyType.STRING:
                    userProps.put(propName, prop.getString());
                    break;
                case PropertyType.LONG:
                    userProps.put(propName, prop.getLong());
                    break;
                case PropertyType.DECIMAL:
                    userProps.put(propName, prop.getDecimal());
                    break;
                case PropertyType.BOOLEAN:
                    userProps.put(propName, prop.getBoolean());
                    break;
                case PropertyType.DATE:
                    userProps.put(propName, prop.getDate());
                    break;
                }
            }
        }

        return userProps;
    }
}
