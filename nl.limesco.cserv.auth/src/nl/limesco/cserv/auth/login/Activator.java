package nl.limesco.cserv.auth.login;

import org.amdatu.auth.tokenprovider.TokenProvider;
import org.apache.felix.dm.DependencyActivatorBase;
import org.apache.felix.dm.DependencyManager;
import org.osgi.framework.BundleContext;
import org.osgi.service.useradmin.UserAdmin;

public class Activator extends DependencyActivatorBase {

	@Override
	public void init(BundleContext context, DependencyManager manager) throws Exception {
		manager.add(createComponent()
				.setInterface(LoginHelperService.class.getName(), null)
				.setImplementation(LoginHelperServiceImpl.class)
				.add(createServiceDependency().setService(UserAdmin.class).setRequired(true))
				.add(createServiceDependency().setService(TokenProvider.class).setRequired(true)));
		
		manager.add(createComponent()
				.setInterface(Object.class.getName(), null)
				.setImplementation(LoginResource.class)
				.add(createServiceDependency().setService(LoginHelperService.class).setRequired(false).setDefaultImplementation(UnavailableLoginHelperService.class)));
	}

	@Override
	public void destroy(BundleContext context, DependencyManager manager) throws Exception {
	}

}
