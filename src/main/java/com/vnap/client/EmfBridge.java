package com.vnap.client;

import com.vnap.VillagerNewsAddonPort;
import net.minecraft.world.entity.LivingEntity;

import java.lang.reflect.Method;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Reflective adapter for Entity Model Features.
 *
 * <p>Upstream imports {@code traben.entity_model_features.EMFAnimationApi} and
 * {@code traben.entity_model_features.utils.EMFEntity} directly. That API shape is
 * EMF 3.x, which targets much newer Minecraft; the 1.20.1 builds expose a different
 * surface, and their exact signatures cannot be verified from this environment.
 *
 * <p>Going through reflection means: the mod compiles without an EMF jar on the
 * classpath, a client without EMF installed still loads (with vanilla villager
 * models and no custom animation), and adapting to whatever EMF 1.20.1 actually
 * exposes is a change to this one file rather than to every call site.
 */
public final class EmfBridge {
	private static final String API_CLASS = "traben.entity_model_features.EMFAnimationApi";

	private static Method registerVariable;
	private static Method getCurrentEntity;
	private static boolean resolved;
	private static boolean available;

	private EmfBridge() {
	}

	public static synchronized boolean available() {
		if (resolved) return available;
		resolved = true;
		try {
			Class<?> api = Class.forName(API_CLASS);
			registerVariable = findMethod(api, "registerSingletonAnimationVariable");
			getCurrentEntity = findMethod(api, "getCurrentEntity");
			available = registerVariable != null && getCurrentEntity != null;
			if (!available) {
				VillagerNewsAddonPort.LOGGER.warn(
					"Entity Model Features is present but exposes an unexpected API; "
						+ "Villager News facial and gesture animation will be inactive.");
			}
		} catch (ClassNotFoundException exception) {
			available = false;
			VillagerNewsAddonPort.LOGGER.warn(
				"Entity Model Features is not installed; Villager News will fall back to vanilla "
					+ "villager models without facial or gesture animation.");
		} catch (RuntimeException exception) {
			available = false;
			VillagerNewsAddonPort.LOGGER.warn("Could not bind to Entity Model Features", exception);
		}
		return available;
	}

	public static void registerAnimationVariable(String name, String description, Supplier<Float> supplier) {
		if (!available()) return;
		try {
			registerVariable.invoke(null, VillagerNewsAddonPort.MOD_ID, name, description, supplier);
		} catch (ReflectiveOperationException | RuntimeException exception) {
			VillagerNewsAddonPort.LOGGER.warn("Could not register EMF animation variable {}", name, exception);
		}
	}

	/** The entity EMF is currently animating, or {@code null} outside a model pass. */
	public static Object currentEntity() {
		if (!available()) return null;
		try {
			return getCurrentEntity.invoke(null);
		} catch (ReflectiveOperationException | RuntimeException exception) {
			return null;
		}
	}

	public static LivingEntity currentLivingEntity() {
		return currentEntity() instanceof LivingEntity living ? living : null;
	}

	/** EMF's {@code etf$getUuid()}, falling back to the entity's own UUID. */
	public static UUID currentUuid() {
		Object entity = currentEntity();
		if (entity == null) return null;
		Object uuid = invokeNoArg(entity, "etf$getUuid");
		if (uuid instanceof UUID value) return value;
		return entity instanceof LivingEntity living ? living.getUUID() : null;
	}

	/** EMF's {@code emf$age()}: entity age in ticks including the partial tick. */
	public static float currentAge(float fallback) {
		Object entity = currentEntity();
		if (entity == null) return fallback;
		Object age = invokeNoArg(entity, "emf$age");
		return age instanceof Number number ? number.floatValue() : fallback;
	}

	private static Object invokeNoArg(Object target, String name) {
		try {
			Method method = target.getClass().getMethod(name);
			method.setAccessible(true);
			return method.invoke(target);
		} catch (ReflectiveOperationException | RuntimeException exception) {
			return null;
		}
	}

	private static Method findMethod(Class<?> owner, String name) {
		for (Method method : owner.getMethods()) {
			if (method.getName().equals(name)) return method;
		}
		return null;
	}
}
