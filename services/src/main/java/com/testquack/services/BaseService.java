package com.testquack.services;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.cp.lock.FencedLock;
import com.testquack.beans.Organization;
import com.testquack.dal.OrganizationRepository;
import com.testquack.services.errors.EntityAccessDeniedException;
import com.testquack.services.errors.EntityNotFoundException;
import com.testquack.services.errors.EntityValidationException;
import com.testquack.services.errors.OrganizationNotSetException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import com.testquack.beans.User;
import com.testquack.beans.Entity;
import com.testquack.beans.Filter;
import com.testquack.beans.Project;
import com.testquack.beans.Launch;
import com.testquack.beans.Event;
import com.testquack.beans.TestCase;
import com.testquack.dal.Logger;
import com.testquack.dal.CommonRepository;
import com.testquack.dal.ProjectRepository;
import com.testquack.dal.UserRepository;
import com.testquack.dal.RoleCapabilityRepository;
import ru.greatbit.whoru.auth.Session;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.stream.Collectors;

import static java.lang.String.format;
import static java.util.stream.Collectors.joining;
import static org.apache.commons.lang3.StringUtils.isEmpty;

public abstract class BaseService<E extends Entity> {

    public final static String CURRENT_ORGANIZATION_KEY = "currentOrganization";
    public final static String ORGANIZATIONS_KEY = "organizations";
    public final static String ORGANIZATIONS_ENABLED_KEY = "organizationsEnabled";

    @Value("${quack.organizations.enabled}")
    protected boolean organizationsEnabled;

    @Value("${entity.lock.ttl.min}")
    protected long lockTtl;

    @Value("${mongo.replicaSet}")
    protected String mongoReplicaSet;

    @Value("${mongo.username}")
    protected String mongousername;

    @Value("${mongo.password}")
    protected String mongopassword;

    @Value("${mongo.dbname}")
    protected String mongodbname;

    public String getMongoReplicaSet() {
       return mongoReplicaSet;
    }

    public String getMongoUsername() {
       return mongousername;
    }

    public String getMongoPassword() {
       return mongopassword;
    }

    public String getMongoDBName() {
       return mongodbname;
    }

    @Autowired
    protected HazelcastInstance hazelcastInstance;

    @Autowired
    protected UserRepository userRepository;

    @Autowired
    protected RoleCapabilityRepository roleCapRepository;

    @Autowired
    protected ProjectRepository projectRepository;

    @Autowired
    protected OrganizationRepository organizationRepository;

    protected abstract CommonRepository<E> getRepository();

    public List<E> findAll(Session session, String projectId){
        throw new UnsupportedOperationException();
    }

    public List<E> findFiltered(Session session, String projectId, Filter filter){
Logger.info("BaseService:findFiltered - projectId: " + projectId);
        return getRepository().find(getCurrOrganizationId(session), projectId, filter).stream().map(entity -> beforeReturn(session, projectId, entity)).collect(Collectors.toList());
    }

    public E findOneUnfiltered(Session session, String projectId, String id){
Logger.info("BaseService:findOneUnfiltered - projectId, id: " + projectId + ", " + id);
        E entity = getRepository().findOne(getCurrOrganizationId(session), projectId, id);
        if (entity == null){
            throw new EntityNotFoundException();
        }
        Logger.info("BaseService:findOneUnfiltered -after getRepo");

        if (userCanRead(session, projectId, entity) == false) {
             throw new EntityAccessDeniedException(
                    format("User %s can't read entity %s", session.getPerson().getLogin(), id)
            );
        }
Logger.info("BaseService:findOneUnfiltered - after userCanRead call");
        return entity;
    }

    public E findOne(Session session, String projectId, String id){
Logger.info("BaseService::findOne");
        E entity = findOneUnfiltered(session, projectId, id);
        return beforeReturn(session, projectId, entity);
    }

    public E save(Session user, String projectId, E entity){
Logger.info("BaseService::save 1 - session, projectId: " + user + ", " + projectId);
Logger.info("BaseService::save 1 - isAdmin: " + isAdmin(user));
Logger.info("BaseService::save 1 - entity: " + entity);
        if (!userCanSave(user, projectId, entity)) {
           throw new EntityAccessDeniedException(
               format("User %s can't save entity %s", user.getPerson().getLogin(), entity.getId()));
        } else if (entity instanceof User) {
          User userEntity = (User)entity;
Logger.info("entity instanceof User");
          if (isAdmin(user) == false) {
             if (user.getPerson().getLogin().equals(userEntity.getLogin())) {
              // permissions allowed to write (change) your own password
Logger.info("Permissions allowed to change your own password");
              }
           }
        }
Logger.info("user CAN save 1: " + user);
        return isEmpty(entity.getId()) ?
                create(user, projectId, entity) :
                update(user, projectId, entity, (origEnt, newEnt) -> newEnt);
    }

    public Collection<E> save(Session user, String projectId, Collection<E> entities){
Logger.info("BaseService::save 2 - session: " + user);
        if (userCanSave(user, projectId, entities) == false) {
Logger.info("user CANNOT save: " + user);
               throw new EntityAccessDeniedException(
                       format("User %s can't save entities %s",
                            user.getPerson().getLogin(),
                            entities.stream().map(obj -> obj == null ? "null" : obj.toString()).collect(joining(", ")))
               );
        }
Logger.info("user CAN save: " + user);
        return getRepository().save(getCurrOrganizationId(user), projectId, entities);
    }


    public void delete(Session session, String projectId, String id){
Logger.info("BaseService:delete - projectId, id: " + projectId + ", " + id);
        beforeDelete(session, projectId, id);
Logger.info("BaseService:delete - after beforeDelete");
        if (userCanDelete(session, projectId, id) == false) {
               throw new EntityAccessDeniedException(
                    format("User %s can't delete entity %s", session.getPerson().getLogin(), id)
            );
        }
Logger.info("BaseService:delete - after  userCanDelete");
        E entity = findOne(session, projectId, id);
Logger.info("BaseService:delete - after findOne");
        getRepository().delete(getCurrOrganizationId(session), projectId, entity.getId());
        afterDelete(session, projectId, id);
Logger.info("BaseService:delete - after afterDelete");
    }

    public long count(Session session, String projectId, Filter filter){


        return userCanReadProject(session, projectId) ?
            getRepository().count(getCurrOrganizationId(session), projectId, filter) : 0;

    }

    protected boolean userCanRead(Session session, String projectId, E entity){
       return userCanReadProject(session, projectId);
    }

    protected boolean userCanReadProject(Session session, String projectId){

Logger.info("BaseService:userCanReadProject - session.person: " + session.getPerson());
Logger.info("BaseService:userCanReadProject - projectId: " + projectId);

       if (isAdmin(session) == false) {
          if (UserSecurity.allowUserReadRequest(
             getCurrOrganizationId(session),
             userRepository, roleCapRepository, projectId, 
             session.getPerson().getLogin())) {

             return userCanAccessProjectCommon(session, projectId);
          } else {
             return false;
          }
       }

       return true;
    }

    protected boolean userCanUpdateProject(Session session, String projectId, 
                                           Collection<E> entities) {

Logger.info("BaseService:userCanUpdateProject - session.person: " + session.getPerson());
Logger.info("BaseService:userCanUpdateProject - projectId: " + projectId);

       if (isAdmin(session) == false) {
Logger.info("BaseService:userCanUpdateProject - not an admin user");
          boolean rc = true;
          Iterator<E> it = entities.iterator();
          while (rc == true && it.hasNext()) {
             E entity = it.next();
Logger.info("BaseService:userCanUpdateProject - ready to call userCanUpdateProject");
             rc = userCanUpdateProject(session, projectId, entity);
          }
Logger.info("BaseService:userCanUpdateProject - end of isadmin=false branch - rc: " + rc);
          return rc;
       }  else {
Logger.info("BaseService:userCanUpdateProject - admin user");
          return true;
       }

    }


    protected boolean userCanUpdateProject(Session session, String projectId, 
                                           E entity) {

Logger.info("BaseService:userCanUpdateProject - session.person: " + session.getPerson());
Logger.info("BaseService:userCanUpdateProject - projectId: " + projectId);
Logger.info("BaseService:userCanUpdateProject - entity: " + entity);

        if (isAdmin(session)) {
Logger.info("BaseService:userCanUpdateProject - admin user");
           return true;
        } else if (entity instanceof TestCase) {
Logger.info("userCanUpdateProject - entity is a TestCase"); 
           TestCase testcaseEntity = (TestCase)entity;
           if (testcaseEntity.isLocked() == true) {
              Logger.info("userCanUpdateProject - entity is a locked TestCase"); 
              return false;
           }
        } else if ((entity instanceof Launch) || (entity instanceof Event)) {

	   if (UserSecurity.allowLaunchWriteRequest(session.getPerson().getRoles(), entity) == false) {
              return false;
	   }

           Logger.info("userCanUpdateProject - entity is a Launch or Event");
           return true;
        }

Logger.info("userCanUpdateProject - before userWriteRequest call"); 
        if (UserSecurity.allowUserWriteRequest(
                         getCurrOrganizationId(session),
                         userRepository, roleCapRepository, projectId, 
                         session.getPerson().getLogin())) {
Logger.info("BaseService:userCanUpdateProject - ready to call userCanAccessProjectCommon");
           return userCanAccessProjectCommon(session, projectId);
        }

        return false;

    }
    protected boolean userCanAccessProjectCommon(Session session, String projectId){
Logger.info("BaseService:userCanAccessProjectCommon - session.person: " + session.getPerson());
Logger.info("BaseService:userCanAccessProjectCommon - session.isIsAdmin: " + session.isIsAdmin());

        Organization organization = organizationRepository.findOne(null, null, getCurrOrganizationId(session));
Logger.info("BaseService::userCanAccessProjectCommon - after findOne");

        if (!isUserInOrganization(session, organization)){
            return false;
        }
Logger.info("BaseService::userCanAccessProjectCommon - after isUserOrganization");

        if (isUserOrganizationAdmin(session, organization)){
            return true;
        }
Logger.info("BaseService::userCanAccessProjectCommon - after isUserOrganizationAdmin");

        return true;
    }
    protected boolean userCanSave(Session session, String projectId, E entity){
Logger.info("BaseService::userCanSave 1 - session: " + session);
        return isAdmin(session)|| userCanUpdateProject(session, projectId, entity);
    }
    protected boolean userCanSave(Session session, String projectId, Collection<E> entities) {
Logger.info("BaseService::userCanSave 2 - session, role: " + session);
        return isAdmin(session) || userCanUpdateProject(session, projectId, 
                                   entities);
    }
    protected boolean userCanDelete(Session session, String projectId, String id){
Logger.info("BaseService::userCanDelete - session, projectId, id: " + session + "," + projectId + ", " + id);

        E entity = getRepository().findOne(getCurrOrganizationId(session), 
                   projectId, id);

        return isAdmin(session) || userCanUpdateProject(session, projectId, entity);
    }
    protected boolean userCanCreate(Session session, String projectId, E entity){
        return isAdmin(session) || userCanUpdateProject(session, projectId, entity);
    }
    protected boolean userCanUpdate(Session session, String projectId, E entity){
        return isAdmin(session) || userCanUpdateProject(session, projectId, entity);
    }

    protected void beforeCreate(Session session, String projectId, E entity){
        if (!isEmpty(entity.getId()) && getRepository().exists(getCurrOrganizationId(session), projectId, entity.getId())){
            throw new EntityValidationException(format("Entity with id [%s] already exists", entity.getId()));
        }
        entity.setCreatedTime(System.currentTimeMillis());
        entity.setCreatedBy(session.getPerson().getLogin());
        if (entity.getName() == "") {
            entity.setName(entity.getClass().getSimpleName());
        }
    }

    protected void afterCreate(Session session, String projectId, E entity) {
    }

    protected void beforeUpdate(Session session, String projectId, E existingEntity, E entity) {
    }

    protected void afterUpdate(Session session, String projectId, E previousEntity, E entity) {
    }

    protected void beforeSave(Session session, String projectId, E entity) {
        entity.setLastModifiedTime(System.currentTimeMillis());
        entity.setLastModifiedBy(session.getLogin());
    }

    protected void afterSave(Session session, String projectId, E entity) {
    }

    protected void beforeDelete(Session session, String projectId, String id) {
    }

    protected void afterDelete(Session session, String projectId, String id) {
    }

    protected E beforeReturn(Session session, String projectId, E entity) {
        return entity;
    }


    protected boolean validateEntity(E ent){
        return true;
    }

    protected E create(Session session, String projectId, E entity){
Logger.info("BaseService::create - session: " + session);
Logger.info("BaseService::create - projectId: " + projectId);
Logger.info("BaseService::create - entity: " + entity);

        beforeCreate(session, projectId, entity);
Logger.info("BaseService::create - after beforeCreate call");

        if (userCanCreate(session, projectId, entity) == false) {
            throw new EntityAccessDeniedException(getAccessDeniedMessage(session, entity, "CREATE"));
        }


Logger.info("BaseService::create - after userCanCreate call");
        entity = doSave(session, projectId, entity);
Logger.info("BaseService::create - after doSave call - entity: " + entity);
        afterCreate(session, projectId, entity);
        return entity;
    }

    protected E update(Session session, String projectId, E entity){
        return update(session, projectId, entity, (savedEnt, newEnt) -> newEnt);
    }

    protected E update(Session session, String projectId, E entity, UpdatableEntityConvertor converter) {
        if (!userCanUpdate(session, projectId, entity)) {
            throw new EntityAccessDeniedException(getAccessDeniedMessage(session, entity, "UPDATE"));
        }
        FencedLock lock = hazelcastInstance.getCPSubsystem().getLock(entity.getClass() + entity.getId());
        try{
            lock.tryLock(lockTtl, TimeUnit.MINUTES);
            E existingEntity = findOneUnfiltered(session, projectId, entity.getId());
            if (existingEntity != null) {
                if (existingEntity.getLastModifiedTime() > entity.getLastModifiedTime()) {
                    throw new EntityValidationException("Entity has been changed previously. Changes will cause lost updates.");
                }
                entity = (E) converter.transform(existingEntity, entity);
            }
Logger.info("BaseService::update - entity: " + entity);
Logger.info("BaseService::update - existingEntity: " + existingEntity);

            beforeUpdate(session, projectId, existingEntity, entity);
            entity = doSave(session, projectId, entity);
            afterUpdate(session, projectId, existingEntity, entity);
            return entity;
        } finally {
            lock.unlock();
        }
    }

    private E doSave(Session session, String projectId, E entity){
Logger.info("BaseService::doSave start - projectId: " + projectId);
Logger.info("BaseService::doSave start - entity: " + entity);
        beforeSave(session, projectId, entity);
        if (validateEntity(entity) || (entity instanceof Event)) {
Logger.info("BaseService::doSave after validateEntity");
            entity = getRepository().save(getCurrOrganizationId(session), projectId, entity);
Logger.info("BaseService::doSave after save");
            afterSave(session, projectId, entity);
            return entity;
        } else throw new EntityValidationException(getAccessDeniedMessage(session, entity, "SAVE"));
    }

    protected String getAccessDeniedMessage(Session session, E ent, String action){
        String login = session != null && session.getPerson() != null ? session.getPerson().getLogin() : "unknown";
        String entId = ent != null && ent.getId() != null ? ent.getId().toString() : "new entity";
        return format("User %s doesn't have %s permissions on %s", login, action, entId);
    }

    protected boolean exists(Session session, String projectId, String entityId) {
        return getRepository().exists(getCurrOrganizationId(session), projectId, entityId);
    }

    public void delete(Session session, String projectId, Filter filter) {
Logger.info("BaseService.delete - projectId: " + projectId);
        List<E> entityList = findFiltered(session, projectId, filter);

        Logger.info("BaseService.delete - after findFiltered call");

        if (userCanUpdateProject(session, projectId, entityList)) {
Logger.info("BaseService.delete - after call of userCanUpdateProject");
           findFiltered(session, projectId, filter).forEach(entity -> {
                  getRepository().delete(getCurrOrganizationId(session), projectId, 
                  entity.getId());
           });
        }
        Logger.info("BaseService.delete - end of method");
    }

    public String getCurrOrganizationId(Session session){
        return (String) session.getMetainfo().get(CURRENT_ORGANIZATION_KEY);
    }

    protected boolean isUserInOrganization(Session session){
        return isUserInOrganization(session, getCurrOrganizationId(session));
    }

    protected boolean isUserInOrganization(Session session, String organizationId){
        if (organizationsEnabled){
            if (organizationId == null){
                throw new OrganizationNotSetException("Organization not set for session");
            }
            Organization organization = organizationRepository.findOne(null, null, organizationId);
            return isUserInOrganization(session, organization);
        }
        return true;
    }

    protected boolean isUserInOrganization(Session session, Organization organization){
        if (organizationsEnabled) {
            if (organization == null) {
                throw new EntityNotFoundException(format("Organization %s does not exist", organization.getId()));
            }
            return organization.getAllowedGroups().stream().anyMatch(session.getPerson().getGroups()::contains) ||
                    organization.getAllowedUsers().stream().anyMatch(session.getPerson().getLogin()::equals) ||
                    organization.getAdmins().stream().anyMatch(session.getPerson().getLogin()::equals);
        }
        return true;
    }

    protected boolean isUserOrganizationAdmin(Session session){
        if (organizationsEnabled){
            String currOrganizationId = getCurrOrganizationId(session);
            if (currOrganizationId == null){
                throw new OrganizationNotSetException("Organization not set for session");
            }
            Organization organization = organizationRepository.findOne(null, null, currOrganizationId);
            return isUserOrganizationAdmin(session, organization);
        }
        return false;
    }

    protected boolean isUserOrganizationAdmin(Session session, Organization organization){
        if (organizationsEnabled) {
            return organization.getAdmins().stream().anyMatch(session.getPerson().getLogin()::equals);
        } return false;
    }

    protected boolean isAdmin(Session session)
    {
       return session.isIsAdmin() || isRoleAdmin(session);
    }

    protected boolean isRoleAdmin(Session session)
    {
       List<String> roles = session.getPerson().getRoles();

       for (String role : roles)
       {
          if (role.toLowerCase().equals("admin")) {
             return true;
          }
       }
       return false;
    }

}
