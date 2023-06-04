/*
 * Copyright 2002-2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.beans.factory.support;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.BeanCreationNotAllowedException;
import org.springframework.beans.factory.BeanCurrentlyInCreationException;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectFactory;
import org.springframework.beans.factory.config.SingletonBeanRegistry;
import org.springframework.core.SimpleAliasRegistry;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/**
 * 对接口 SingletonBeanRegistry 各函数的实现
 * 共享 bean 实例的通用注册表，实现了 SingletonBeanRegistry。允许注册单例实例，
 * 该实例应该为注册中心的所有调用者共享，并通过 bean 名称获得。还支持一次性 bean 实例
 * 的注册(它可能对应于已注册的单例，也可能不对应于已注册的单例)，在注册表关闭时销毁。
 * 可以注册 bean 之间的依赖关系，以强制执行适当的关闭顺序
 *
 * Generic registry for shared bean instances, implementing the
 * {@link org.springframework.beans.factory.config.SingletonBeanRegistry}.
 * Allows for registering singleton instances that should be shared
 * for all callers of the registry, to be obtained via bean name.
 *
 * <p>Also supports registration of
 * {@link org.springframework.beans.factory.DisposableBean} instances,
 * (which might or might not correspond to registered singletons),
 * to be destroyed on shutdown of the registry. Dependencies between
 * beans can be registered to enforce an appropriate shutdown order.
 *
 * <p>This class mainly serves as base class for
 * {@link org.springframework.beans.factory.BeanFactory} implementations,
 * factoring out the common management of singleton bean instances. Note that
 * the {@link org.springframework.beans.factory.config.ConfigurableBeanFactory}
 * interface extends the {@link SingletonBeanRegistry} interface.
 *
 * <p>Note that this class assumes neither a bean definition concept
 * nor a specific creation process for bean instances, in contrast to
 * {@link AbstractBeanFactory} and {@link DefaultListableBeanFactory}
 * (which inherit from it). Can alternatively also be used as a nested
 * helper to delegate to.
 *
 * @author Juergen Hoeller
 * @since 2.0
 * @see #registerSingleton
 * @see #registerDisposableBean
 * @see org.springframework.beans.factory.DisposableBean
 * @see org.springframework.beans.factory.config.ConfigurableBeanFactory
 */
public class DefaultSingletonBeanRegistry extends SimpleAliasRegistry implements SingletonBeanRegistry {

	/** Maximum number of suppressed exceptions to preserve. */
	private static final int SUPPRESSED_EXCEPTIONS_LIMIT = 100;


	/**
	 * 一级缓存
	 * 用于保存 BeanName和创建 bean 实例之间的关系
	 */
	/** Cache of singleton objects: bean name to bean instance. */
	private final Map<String, Object> singletonObjects = new ConcurrentHashMap<>(256);

	/**
	 * 三级缓存
	 * 用于保存 BeanName和创建 bean 的工厂之间的关系
	 */
	/** Cache of singleton factories: bean name to ObjectFactory. */
	private final Map<String, ObjectFactory<?>> singletonFactories = new HashMap<>(16);

	/**
	 * 二级缓存
	 * 保存 BeanName和创建 bean 实例之间的关系，与 singletonFactories 的不同之处在于，当一个单例 bean 被放到这里之后，那么当 bean 还在创建过程中
	 * 就可以通过 getBean 方法获取到，可以方便进行循环依赖的检测
	 */
	/** Cache of early singleton objects: bean name to bean instance. */
	private final Map<String, Object> earlySingletonObjects = new ConcurrentHashMap<>(16);

	/**
	 * 用来保存当前所有已经注册的 bean
	 */
	/** Set of registered singletons, containing the bean names in registration order. */
	private final Set<String> registeredSingletons = new LinkedHashSet<>(256);

	/**
	 * 正在创建过程中的 beanName 集合
	 */
	/** Names of beans that are currently in creation. */
	private final Set<String> singletonsCurrentlyInCreation =
			Collections.newSetFromMap(new ConcurrentHashMap<>(16));

	/**
	 * 当前在创建检查中排除的 bean 名
	 */
	/** Names of beans currently excluded from in creation checks. */
	private final Set<String> inCreationCheckExclusions =
			Collections.newSetFromMap(new ConcurrentHashMap<>(16));

	/**
	 * 抑制的异常列表，可用于关联相关原因
	 */
	/** Collection of suppressed Exceptions, available for associating related causes. */
	@Nullable
	private Set<Exception> suppressedExceptions;

	/**
	 * 指示我们当前是否在 destroySingletons 中的标志
	 */
	/** Flag that indicates whether we're currently within destroySingletons. */
	private boolean singletonsCurrentlyInDestruction = false;

	/**
	 * 一次性 Bean 实例：bean 名称 - DisposableBean 实例
	 */
	/** Disposable bean instances: bean name to disposable instance. */
	private final Map<String, Object> disposableBeans = new LinkedHashMap<>();

	/**
	 * 在包含的 Bean 名称之间映射：bean名称 - Bean 包含的 Bean 名称集
	 */
	/** Map between containing bean names: bean name to Set of bean names that the bean contains. */
	private final Map<String, Set<String>> containedBeanMap = new ConcurrentHashMap<>(16);

	/**
	 * 存储 bean 名到该 bean 名所要依赖的 bean名 的 Map
	 */
	/** Map between dependent bean names: bean name to Set of dependent bean names. */
	private final Map<String, Set<String>> dependentBeanMap = new ConcurrentHashMap<>(64);

	/**
	 * 存储 bean名到依赖于该 bean 名的 bean 名 的 Map
	 */
	/** Map between depending bean names: bean name to Set of bean names for the bean's dependencies. */
	private final Map<String, Set<String>> dependenciesForBeanMap = new ConcurrentHashMap<>(64);


	/**
	 * 在给定的 bean 名称下，在 bean 注册器中将给定的现有对象注册为单例
	 */
	@Override
	public void registerSingleton(String beanName, Object singletonObject) throws IllegalStateException {
		Assert.notNull(beanName, "Bean name must not be null");
		Assert.notNull(singletonObject, "Singleton object must not be null");
		// 使用 singletonObjects 作为锁，保证线程安全
		synchronized (this.singletonObjects) {
			// 获取 beanName 在 singletonObjects 中的单例对象
			Object oldObject = this.singletonObjects.get(beanName);
			// 如果成功获得对象
			if (oldObject != null) {
				// 非法状态异常：不能注册对象[singletonObject]，在bean名'beanName'下，已经有对象[oldObject]
				throw new IllegalStateException("Could not register object [" + singletonObject +
						"] under bean name '" + beanName + "': there is already object [" + oldObject + "] bound");
			}
			// 将 beanName 和 singletonObject 的映射关系添加到该工厂的单例缓存中
			addSingleton(beanName, singletonObject);
		}
	}

	/**
	 * 将 beanName 和 singletonObject 的映射关系添加到该工厂的单例缓存中
	 *
	 * Add the given singleton object to the singleton cache of this factory.
	 * <p>To be called for eager registration of singletons.
	 * @param beanName the name of the bean
	 * @param singletonObject the singleton object
	 */
	protected void addSingleton(String beanName, Object singletonObject) {
		synchronized (this.singletonObjects) {
			// 将映射关系添加到单例对象的高速缓存中
			this.singletonObjects.put(beanName, singletonObject);
			// 移除 beanName 在单例工厂缓存中的数据
			this.singletonFactories.remove(beanName);
			// 移除 beanName 在早期单例对象的高速缓存的数据
			this.earlySingletonObjects.remove(beanName);
			// 将 beanName 添加到已注册的单例集中
			this.registeredSingletons.add(beanName);
		}
	}

	/**
	 * 如果需要，添加给定的单例对象工厂来构建指定的单例对象
	 *
	 * Add the given singleton factory for building the specified singleton
	 * if necessary.
	 * <p>To be called for eager registration of singletons, e.g. to be able to
	 * resolve circular references.
	 * @param beanName the name of the bean
	 * @param singletonFactory the factory for the singleton object
	 */
	protected void addSingletonFactory(String beanName, ObjectFactory<?> singletonFactory) {
		Assert.notNull(singletonFactory, "Singleton factory must not be null");
		// 使用 singletonObjects 进行加锁，保证线程安全
		synchronized (this.singletonObjects) {
			// 如果单例对象的高速缓存【beam名称-bean实例】没有 beanName 的对象
			if (!this.singletonObjects.containsKey(beanName)) {
				// 将 beanName,singletonFactory 放到单例工厂的缓存【bean名称 - ObjectFactory】
				this.singletonFactories.put(beanName, singletonFactory);
				// 从早期单例对象的高速缓存【bean名称-bean实例】 移除 beanName 的相关缓存对象
				this.earlySingletonObjects.remove(beanName);
				// 将 beanName 添加已注册的单例集中
				this.registeredSingletons.add(beanName);
			}
		}
	}

	/**
	 * 获取 beanName 的单例对象，并允许创建早期引用
	 */
	@Override
	@Nullable
	public Object getSingleton(String beanName) {
		// 获取 beanName 的单例对象，并允许创建早期引用
		return getSingleton(beanName, true);
	}

	/**
	 * 获取以 beanName 注册的(原始)单例对象
	 *
	 * Return the (raw) singleton object registered under the given name.
	 * <p>Checks already instantiated singletons and also allows for an early
	 * reference to a currently created singleton (resolving a circular reference).
	 * @param beanName the name of the bean to look for
	 * @param allowEarlyReference whether early references should be created or not
	 * @return the registered singleton object, or {@code null} if none found
	 */
	@Nullable
	protected Object getSingleton(String beanName, boolean allowEarlyReference) {
		// 从单例对象缓存中获取 beanName 对应的单例对象
		// Quick check for existing instance without full singleton lock
		Object singletonObject = this.singletonObjects.get(beanName);
		// 如果单例对象缓存中没有，并且该 beanName 对应的单例 bean 正在创建中
		if (singletonObject == null && isSingletonCurrentlyInCreation(beanName)) {
			/**
			 * 从早期单例对象缓存中获取单例对象（之所称成为早期单例对象，是因为 earlySingletonObjects 里
			 * 的对象的都是通过提前曝光的 ObjectFactory 创建出来的，还未进行属性填充等操作）
			 */
			singletonObject = this.earlySingletonObjects.get(beanName);
			// 如果在早期单例对象缓存中也没有，并且允许创建早期单例对象引用
			if (singletonObject == null && allowEarlyReference) {
				// 如果为空，则锁定全局变量并进行处理
				synchronized (this.singletonObjects) {
					// Consistent creation of early reference within full singleton lock
					singletonObject = this.singletonObjects.get(beanName);
					if (singletonObject == null) {
						singletonObject = this.earlySingletonObjects.get(beanName);
						if (singletonObject == null) {
							// 当某些方法需要提前初始化的时候则会调用 addSingletonFactory 方法将对应的 ObjectFactory 初始化策略存储在 singletonFactories
							ObjectFactory<?> singletonFactory = this.singletonFactories.get(beanName);
							if (singletonFactory != null) {
								// 如果存在单例对象工厂，则通过工厂创建一个单例对象
								singletonObject = singletonFactory.getObject();
								// 记录在缓存中，二级缓存和三级缓存的对象不能同时存在
								this.earlySingletonObjects.put(beanName, singletonObject);
								// 从三级缓存中移除
								this.singletonFactories.remove(beanName);
							}
						}
					}
				}
			}
		}
		return singletonObject;
	}

	/**
	 * 返回以给定名称注册的(原始)单例对象，如果尚未注册，则创建并注册一个对象
	 *
	 * Return the (raw) singleton object registered under the given name,
	 * creating and registering a new one if none registered yet.
	 * @param beanName the name of the bean
	 * @param singletonFactory the ObjectFactory to lazily create the singleton
	 * with, if necessary
	 * @return the registered singleton object
	 */
	public Object getSingleton(String beanName, ObjectFactory<?> singletonFactory) {
		// 如果 beanName 为 null，抛出异常
		Assert.notNull(beanName, "Bean name must not be null");
		// 使用单例对象的高速缓存 Map 作为锁，保证线程同步
		synchronized (this.singletonObjects) {
			// 从单例对象的高速缓存 Map 中获取 beanName 对应的单例对象
			Object singletonObject = this.singletonObjects.get(beanName);
			// 如果单例对象获取不到
			if (singletonObject == null) {
				// 如果当前在 destorySingletons中
				if (this.singletonsCurrentlyInDestruction) {
					throw new BeanCreationNotAllowedException(beanName,
							"Singleton bean creation not allowed while singletons of this factory are in destruction " +
							"(Do not request a bean from a BeanFactory in a destroy method implementation!)");
				}
				// 如果当前日志级别时调试
				if (logger.isDebugEnabled()) {
					logger.debug("Creating shared instance of singleton bean '" + beanName + "'");
				}
				// 创建单例之前的回调,默认实现将单例注册为当前正在创建中
				beforeSingletonCreation(beanName);
				// 表示生成了新的单例对象的标记，默认为 false，表示没有生成新的单例对象
				boolean newSingleton = false;
				// 有抑制异常记录标记，没有时为 true，否则为 false
				boolean recordSuppressedExceptions = (this.suppressedExceptions == null);
				// 如果没有抑制异常记录
				if (recordSuppressedExceptions) {
					// 对抑制的异常列表进行实例化(LinkedHashSet)
					this.suppressedExceptions = new LinkedHashSet<>();
				}
				try {
					// 从单例工厂中获取对象
					singletonObject = singletonFactory.getObject();
					// 生成了新的单例对象的标记为true，表示生成了新的单例对象
					newSingleton = true;
				}
				catch (IllegalStateException ex) {
					// Has the singleton object implicitly appeared in the meantime ->
					// if yes, proceed with it since the exception indicates that state.
					/**
					 * 同时，单例对象是否隐式出现 -> 如果是，请继续操作，因为异常表明该状态
					 * 尝试从单例对象的高速缓存 Map 中获取 beanName 的单例对象
					 */
					singletonObject = this.singletonObjects.get(beanName);
					// 如果获取失败，抛出异常
					if (singletonObject == null) {
						throw ex;
					}
				}
				// 捕捉Bean创建异常
				catch (BeanCreationException ex) {
					// 如果没有抑制异常记录
					if (recordSuppressedExceptions) {
						// 遍历抑制的异常列表
						for (Exception suppressedException : this.suppressedExceptions) {
							// 将抑制的异常对象添加到 bean 创建异常 中，这样做的，就是相当于 '因XXX异常导致了Bean创建异常‘ 的说法
							ex.addRelatedCause(suppressedException);
						}
					}
					// 抛出异常
					throw ex;
				}
				finally {
					// 如果没有抑制异常记录
					if (recordSuppressedExceptions) {
						/**
						 * 将抑制的异常列表置为 null，因为 suppressedExceptions 是对应单个 bean 的异常记录，置为 null
						 * 可防止异常信息的混乱
						 */
						this.suppressedExceptions = null;
					}
					// 创建单例后的回调,默认实现将单例标记为不在创建中
					afterSingletonCreation(beanName);
				}
				// 生成了新的单例对象
				if (newSingleton) {
					// 将 beanName 和 singletonObject 的映射关系添加到该工厂的单例缓存中:
					addSingleton(beanName, singletonObject);
				}
			}
			return singletonObject;
		}
	}

	/**
	 * 将要注册的异常对象添加到 抑制异常列表中，注意抑制异常列表【#suppressedExceptions】是Set集合
	 *
	 * Register an exception that happened to get suppressed during the creation of a
	 * singleton bean instance, e.g. a temporary circular reference resolution problem.
	 * <p>The default implementation preserves any given exception in this registry's
	 * collection of suppressed exceptions, up to a limit of 100 exceptions, adding
	 * them as related causes to an eventual top-level {@link BeanCreationException}.
	 * @param ex the Exception to register
	 * @see BeanCreationException#getRelatedCauses()
	 */
	protected void onSuppressedException(Exception ex) {
		// 使用singletonObject同步加锁
		synchronized (this.singletonObjects) {
			// 如果抑制异常列表不为null
			if (this.suppressedExceptions != null && this.suppressedExceptions.size() < SUPPRESSED_EXCEPTIONS_LIMIT) {
				// 将要注册的异常对象添加到抑制异常列表中，注意抑制异常列表是 Set 集合
				this.suppressedExceptions.add(ex);
			}
		}
	}

	/**
	 * 从该工厂单例缓存中删除具有给定名称的 Bean。如果创建失败，则能够清理饿汉式注册的单例
	 *
	 * Remove the bean with the given name from the singleton cache of this factory,
	 * to be able to clean up eager registration of a singleton if creation failed.
	 * @param beanName the name of the bean
	 * @see #getSingletonMutex()
	 */
	protected void removeSingleton(String beanName) {
		// 同步，使用单例对象的高速缓存: beam 名称 -bean 实例作为锁
		synchronized (this.singletonObjects) {
			// 删除单例对象的高速缓存: beam 名称 -bean 实例的对应数据
			this.singletonObjects.remove(beanName);
			// 删除单例工厂的缓存：bean 名称 -ObjectFactory 的对应数据
			this.singletonFactories.remove(beanName);
			// 删除 单例对象的高速缓存: beam 名称 -bean 实例的对应数据
			this.earlySingletonObjects.remove(beanName);
			// 删除已注册的单例集，按照注册顺序包含 bean 名称 的对应数据
			this.registeredSingletons.remove(beanName);
		}
	}

	/**
	 * 只是判断一下 beanName 是否在该 BeanFactory 的单例对象的高速缓存 Map 集合
	 */
	@Override
	public boolean containsSingleton(String beanName) {
		return this.singletonObjects.containsKey(beanName);
	}

	@Override
	public String[] getSingletonNames() {
		synchronized (this.singletonObjects) {
			return StringUtils.toStringArray(this.registeredSingletons);
		}
	}

	@Override
	public int getSingletonCount() {
		synchronized (this.singletonObjects) {
			return this.registeredSingletons.size();
		}
	}


	public void setCurrentlyInCreation(String beanName, boolean inCreation) {
		Assert.notNull(beanName, "Bean name must not be null");
		if (!inCreation) {
			this.inCreationCheckExclusions.add(beanName);
		}
		else {
			this.inCreationCheckExclusions.remove(beanName);
		}
	}

	/**
	 * 给定的 bean 名是否正在创建
	 */
	public boolean isCurrentlyInCreation(String beanName) {
		Assert.notNull(beanName, "Bean name must not be null");
		// 如果当前在创建检查中排除的 bean 名列表中不包含该 beanName 且 beanName 实际上正在创建就返回 true.
		return (!this.inCreationCheckExclusions.contains(beanName) && isActuallyInCreation(beanName));
	}

	/**
	 * 给定的 bean 名实际上是否正在创建
	 */
	protected boolean isActuallyInCreation(String beanName) {
		return isSingletonCurrentlyInCreation(beanName);
	}

	/**
	 * 返回指定的单例 bean 当前是否正在创建（在整个工厂内）
	 *
	 * Return whether the specified singleton bean is currently in creation
	 * (within the entire factory).
	 * @param beanName the name of the bean
	 */
	public boolean isSingletonCurrentlyInCreation(String beanName) {
		// 从当前正在创建的 bean 名称 set 集合中判断 beanName 是否在集合中
		return this.singletonsCurrentlyInCreation.contains(beanName);
	}

	/**
	 * 创建单例之前的回调:
	 * 如果当前在创建检查中的排除 bean 名列表【inCreationCheckExclusions】中不包含该 beanName 且将 beanName 添加到
	 * 当前正在创建的 bean 名称列表【singletonsCurrentlyInCreation】后，出现 beanName 已经在当前正在创建的 bean 名称列表中添加过
	 *
	 * 创建单例之前的回调
	 *
	 * Callback before singleton creation.
	 * <p>The default implementation register the singleton as currently in creation.
	 * @param beanName the name of the singleton about to be created
	 * @see #isSingletonCurrentlyInCreation
	 */
	protected void beforeSingletonCreation(String beanName) {
		/**
		 * 如果当前在创建检查中的排除 bean 名列表中不包含该 beanName 且将 beanName 添加到当前正在创建的 bean 名称列表后，出现
		 * beanName 已经在当前正在创建的 bean 名称列表中添加过
		 */
		if (!this.inCreationCheckExclusions.contains(beanName) && !this.singletonsCurrentlyInCreation.add(beanName)) {
			// 抛出当前正在创建的 Bean 异常
			throw new BeanCurrentlyInCreationException(beanName);
		}
	}

	/**
	 * 创建单例后的回调
	 * 默认实现将单例标记为不在创建中
	 *
	 * Callback after singleton creation.
	 * <p>The default implementation marks the singleton as not in creation anymore.
	 * @param beanName the name of the singleton that has been created
	 * @see #isSingletonCurrentlyInCreation
	 */
	protected void afterSingletonCreation(String beanName) {
		/**
		 * 如果当前在创建检查中的排除 bean 名列表中不包含该 beanName 且将 beanName 从当前正在创建的 bean 名称列表异常后，出现
		 * beanName 已经没在当前正在创建的 bean 名称列表中出现过
		 */
		if (!this.inCreationCheckExclusions.contains(beanName) && !this.singletonsCurrentlyInCreation.remove(beanName)) {
			// 抛出非法状态异常：单例 'beanName' 不是当前正在创建的
			throw new IllegalStateException("Singleton '" + beanName + "' isn't currently in creation");
		}
	}


	/**
	 * 将给定 Bean 添加到注册中心的一次性 Bean 列表中
	 *
	 * Add the given bean to the list of disposable beans in this registry.
	 * <p>Disposable beans usually correspond to registered singletons,
	 * matching the bean name but potentially being a different instance
	 * (for example, a DisposableBean adapter for a singleton that does not
	 * naturally implement Spring's DisposableBean interface).
	 * @param beanName the name of the bean
	 * @param bean the bean instance
	 */
	public void registerDisposableBean(String beanName, DisposableBean bean) {
		// 使用 disposableBeans 加锁，保证线程安全
		synchronized (this.disposableBeans) {
			// 将 beanName,bean 添加到 disposableBeans中
			this.disposableBeans.put(beanName, bean);
		}
	}

	/**
	 * 将 containedBeanName 和 containingBeanName 的包含关系注册到该工厂中
	 *
	 * Register a containment relationship between two beans,
	 * e.g. between an inner bean and its containing outer bean.
	 * <p>Also registers the containing bean as dependent on the contained bean
	 * in terms of destruction order.
	 * @param containedBeanName the name of the contained (inner) bean
	 * @param containingBeanName the name of the containing (outer) bean
	 * @see #registerDependentBean
	 */
	public void registerContainedBean(String containedBeanName, String containingBeanName) {
		// 使用 containedBeanMap 作为锁，保证线程安全
		synchronized (this.containedBeanMap) {
			// 从 containedBeanMap 中获取 containgBeanNamed 的内部 Bean 名列表，没有时创建一个初始化长度为8的 LinkedHashSet 来使用
			Set<String> containedBeans =
					this.containedBeanMap.computeIfAbsent(containingBeanName, k -> new LinkedHashSet<>(8));
			// 将 containedBeanName 添加到 containedBeans 中，如果已经添加过了，就直接返回
			if (!containedBeans.add(containedBeanName)) {
				return;
			}
		}
		// 注册 containedBeanName 与 containingBeanName 的依赖关系
		registerDependentBean(containedBeanName, containingBeanName);
	}

	/**
	 *  注册 beanName 与 dependentBeanNamed 的依赖关系
	 *
	 * Register a dependent bean for the given bean,
	 * to be destroyed before the given bean is destroyed.
	 * @param beanName the name of the bean
	 * @param dependentBeanName the name of the dependent bean
	 */
	public void registerDependentBean(String beanName, String dependentBeanName) {
		// 获取 name 的最终别名或者是全类名
		String canonicalName = canonicalName(beanName);

		// 使用存储 bean 名到该 bean 名所要依赖的 bean 名的 Map 作为锁，保证线程安全
		synchronized (this.dependentBeanMap) {
			// 获取 canonicalName 对应的用于存储依赖 Bean 名的 Set 集合，如果没有就创建一个 LinkedHashSet，并与 canonicalName 绑定到 dependentBeans 中
			Set<String> dependentBeans =
					this.dependentBeanMap.computeIfAbsent(canonicalName, k -> new LinkedHashSet<>(8));
			// 如果 dependentBeans 已经添加过来了 dependentBeanName，就结束该方法，不执行后面操作。
			if (!dependentBeans.add(dependentBeanName)) {
				return;
			}
		}

		//使用 Bean 依赖关系 Map 作为锁，保证线程安全
		synchronized (this.dependenciesForBeanMap) {
			//添加 dependendtBeanName 依赖于 cannoicalName 的映射关系到 存储 bean 名到依赖于该 bean 名的 bean 名 的 Map 中
			Set<String> dependenciesForBean =
					this.dependenciesForBeanMap.computeIfAbsent(dependentBeanName, k -> new LinkedHashSet<>(8));
			dependenciesForBean.add(canonicalName);
		}
	}

	/**
	 * 判断 beanName 是否已注册依赖于 dependentBeanName 的关系
	 *
	 * Determine whether the specified dependent bean has been registered as
	 * dependent on the given bean or on any of its transitive dependencies.
	 * @param beanName the name of the bean to check
	 * @param dependentBeanName the name of the dependent bean
	 * @since 4.0
	 */
	protected boolean isDependent(String beanName, String dependentBeanName) {
		// 使用依赖 bean 关系 Map 作为锁，保证线程安全
		synchronized (this.dependentBeanMap) {
			return isDependent(beanName, dependentBeanName, null);
		}
	}

	/**
	 * 确定指定的依赖 bean 是否已注册为依赖于给定 bean 或其任何传递依赖
	 */
	private boolean isDependent(String beanName, String dependentBeanName, @Nullable Set<String> alreadySeen) {
		// 如果 alreadySeen 已经包含该 beanName，返回 false
		if (alreadySeen != null && alreadySeen.contains(beanName)) {
			return false;
		}
		// 获取 name 的最终别名或者是全类名
		String canonicalName = canonicalName(beanName);
		// 从依赖 bean 关系 Map 中获取 canonicalName 的依赖 bean 名
		Set<String> dependentBeans = this.dependentBeanMap.get(canonicalName);
		// 如果没有拿到依赖 bean，返回 false,表示不依赖
		if (dependentBeans == null) {
			return false;
		}
		// 如果依赖 bean 名中包含 dependentBeanName，返回 true，表示是依赖
		if (dependentBeans.contains(dependentBeanName)) {
			return true;
		}
		// 遍历依赖 bean 名
		for (String transitiveDependency : dependentBeans) {
			// 如果 alreadySeen 为 null,就实例化一个 HashSet
			if (alreadySeen == null) {
				alreadySeen = new HashSet<>();
			}
			// 将 beanName 添加到 alreadySeen
			alreadySeen.add(beanName);
			// 通过递归的方式检查 dependentBeanName 是否依赖 transitiveDependency,是就返回 true
			if (isDependent(transitiveDependency, dependentBeanName, alreadySeen)) {
				return true;
			}
		}
		// 返回false，表示不是依赖
		return false;
	}

	/**
	 * 确定是否已经为给定名称注册了依赖 Bean 关系
	 *
	 * Determine whether a dependent bean has been registered for the given name.
	 * @param beanName the name of the bean to check
	 */
	protected boolean hasDependentBean(String beanName) {
		return this.dependentBeanMap.containsKey(beanName);
	}

	/**
	 * 如果有的话，返回依赖于指定 Bean 的所有 Bean 名称
	 *
	 * Return the names of all beans which depend on the specified bean, if any.
	 * @param beanName the name of the bean
	 * @return the array of dependent bean names, or an empty array if none
	 */
	public String[] getDependentBeans(String beanName) {
		// 从 dependentBeanMap 中获取依赖 Bean 名称的数组
		Set<String> dependentBeans = this.dependentBeanMap.get(beanName);
		// 如果 dependentBeans 为 null
		if (dependentBeans == null) {
			return new String[0];
		}
		// 使用 dependentBeanMap 进行加锁，以保证 Set 转数组时的线程安全
		synchronized (this.dependentBeanMap) {
			// 将 dependentBeans 转换为数组
			return StringUtils.toStringArray(dependentBeans);
		}
	}

	/**
	 * 返回指定 bean 所依赖的所有 bean 的名称(如果有的话)
	 *
	 * Return the names of all beans that the specified bean depends on, if any.
	 * @param beanName the name of the bean
	 * @return the array of names of beans which the bean depends on,
	 * or an empty array if none
	 */
	public String[] getDependenciesForBean(String beanName) {
		/**
		 * dependenciesForBeanMap：存储 bean 名到依赖于该 bean 名的 bean 名的 Map
		 * 从 dependenciesForBeanMap 中获取 beanName 的所依赖的 bean 的名称数组
		 */
		Set<String> dependenciesForBean = this.dependenciesForBeanMap.get(beanName);
		// 如果 dependenciesForBean 为 null
		if (dependenciesForBean == null) {
			// 返回空字符串数组
			return new String[0];
		}
		// 使用 dependenciesForBeanMap 加锁，保证线程安全
		synchronized (this.dependenciesForBeanMap) {
			// 将 dependenciesForBean 转换为字符串数组返回出去
			return StringUtils.toStringArray(dependenciesForBean);
		}
	}

	public void destroySingletons() {
		if (logger.isTraceEnabled()) {
			logger.trace("Destroying singletons in " + this);
		}
		// 同步，使用单例对象的高速缓存: beam 名称 -bean 实例作为锁
		synchronized (this.singletonObjects) {
			// 将当前是否在 destroySingletons 中的标志设置为 true，表明正在 destroySingletons
			this.singletonsCurrentlyInDestruction = true;
		}

		String[] disposableBeanNames;
		// 同步,使用一次性 Bean 实例缓存：bean 名称 -DisposableBean 实例作为锁
		synchronized (this.disposableBeans) {
			// 复制 disposableBean 的 key 集到一个 String 数组
			disposableBeanNames = StringUtils.toStringArray(this.disposableBeans.keySet());
		}
		// 遍历 disposableBeanNames
		for (int i = disposableBeanNames.length - 1; i >= 0; i--) {
			/**
			 * 销毁 disposableBeanNames[i])。先销毁依赖于 disposableBeanNames[i]) 的 bean,
			 * 然后再销毁 bean。
			 */
			destroySingleton(disposableBeanNames[i]);
		}

		// 清空在包含的 Bean 名称之间映射：bean 名称 -Bean 包含的 Bean 名称集
		this.containedBeanMap.clear();
		// 清空在相关的 Bean 名称之间映射：bean 名称-一组相关的 Bean 名称
		this.dependentBeanMap.clear();
		// 清空在相关的 Bean 名称之j键映射：bean 名称 bean 依赖项的 Bean 名称集
		this.dependenciesForBeanMap.clear();

		// 清除此注册表中所有缓存的单例实例
		clearSingletonCache();
	}

	/**
	 * 清除此注册表中所有缓存的单例实例
	 *
	 * Clear all cached singleton instances in this registry.
	 * @since 4.3.15
	 */
	protected void clearSingletonCache() {
		// 加锁，使用单例对象的高速缓存: beam 名称 -bean 实例作为锁
		synchronized (this.singletonObjects) {
			// 清空单例对象的高速缓存: beam 名称 -bean 实例
			this.singletonObjects.clear();
			// 清空单例工厂的缓存：bean 名称 -ObjectFactory
			this.singletonFactories.clear();
			// 清空早期单例对象的高速缓存：bean 名称 -bean 实例
			this.earlySingletonObjects.clear();
			// 清空已注册的单例集，按照注册顺序包含 bean 名称
			this.registeredSingletons.clear();
			// 设置当前是否在 destroySingletons 中的标志为 false
			this.singletonsCurrentlyInDestruction = false;
		}
	}

	/**
	 * 销毁给定的 bean。如果找到相应的一次性 Bean 实例，则委托给{@code destoryBean}
	 *
	 * Destroy the given bean. Delegates to {@code destroyBean}
	 * if a corresponding disposable bean instance is found.
	 * @param beanName the name of the bean
	 * @see #destroyBean
	 */
	public void destroySingleton(String beanName) {
		// 删除给定名称的已注册的单例（如果有）
		// Remove a registered singleton of the given name, if any.
		removeSingleton(beanName);

		/**
		 * 销毁相应的 DisposableBean 实例
		 *  DisposableBean：要在销毁时释放资源的 bean 所实现的接口.包括已注册为一次性的内部 bean
		 *  在工厂关闭时调用
		 */
		// Destroy the corresponding DisposableBean instance.
		DisposableBean disposableBean;
		// 同步，将 一次性Bean实例：bean 名称 -DisposableBean 实例作为锁
		synchronized (this.disposableBeans) {
			// 从 disposableBeans 移除出 disposableBean 对象
			disposableBean = (DisposableBean) this.disposableBeans.remove(beanName);
		}
		// 销毁给定 bean，必须先销毁依赖于给定 bean 的 bean，然后再销毁 bean
		destroyBean(beanName, disposableBean);
	}

	/**
	 * 销毁给定 bean，必须先销毁依赖于给定 bean 的 bean，然后再销毁 bean，不应抛出任何异常
	 *
	 * Destroy the given bean. Must destroy beans that depend on the given
	 * bean before the bean itself. Should not throw any exceptions.
	 * @param beanName the name of the bean
	 * @param bean the bean instance to destroy
	 */
	protected void destroyBean(String beanName, @Nullable DisposableBean bean) {
		// 先触发从依赖的 bean 的破坏
		// Trigger destruction of dependent beans first...
		Set<String> dependencies;
		// 同步,使用 在相关的 Bean 名称之间映射：bean 名称- 一组相关的Bean名称作为锁
		synchronized (this.dependentBeanMap) {
			/**
			 * 在完全同步内以确保断开连续集
			 * 从 dependentBeanMap 中移除出 beanName 对应的依赖 beanName 集
			 */
			// Within full synchronization in order to guarantee a disconnected Set
			dependencies = this.dependentBeanMap.remove(beanName);
		}
		// 如果存在依赖的 beanName 集
		if (dependencies != null) {
			if (logger.isTraceEnabled()) {
				logger.trace("Retrieved dependent beans for bean '" + beanName + "': " + dependencies);
			}
			// 遍历依赖的 BeanName
			for (String dependentBeanName : dependencies) {
				// 递归删除 dependentBeanName 的实例
				destroySingleton(dependentBeanName);
			}
		}

		// 实现上现在销毁的 bean
		// Actually destroy the bean now...
		if (bean != null) {
			try {
				// 调用销毁方法
				bean.destroy();
			}
			catch (Throwable ex) {
				// 抛出异常时，打印出警告信息
				if (logger.isWarnEnabled()) {
					logger.warn("Destruction of bean with name '" + beanName + "' threw an exception", ex);
				}
			}
		}

		// 触发销毁所包含的 bean
		// Trigger destruction of contained beans...
		Set<String> containedBeans;
		// 同步，使用 在包含的 Bean 名称之间映射：bean 名称 -Bean 包含的 Bean 名称集作为锁
		synchronized (this.containedBeanMap) {
			// Within full synchronization in order to guarantee a disconnected Set
			containedBeans = this.containedBeanMap.remove(beanName);
		}
		// 如果存在 BeanName 包含的 bean 名称集
		if (containedBeans != null) {
			// 遍历 BeanName 包含的 bean 名称集
			for (String containedBeanName : containedBeans) {
				// 递归删除 containedBeanName 的实例
				destroySingleton(containedBeanName);
			}
		}

		/**
		 * 从其他 bean 的依赖项中删除破坏的 bean
		 * 同步，在相关的 Bean 名称之间映射：bean 名称- 一组相关的 Bean 名称作为锁
		 */
		// Remove destroyed bean from other beans' dependencies.
		synchronized (this.dependentBeanMap) {
			// 遍历 dependentBeanMap 的元素
			for (Iterator<Map.Entry<String, Set<String>>> it = this.dependentBeanMap.entrySet().iterator(); it.hasNext();) {
				Map.Entry<String, Set<String>> entry = it.next();
				Set<String> dependenciesToClean = entry.getValue();
				// 从其它 bean 的依赖 bean 集合中移除 beanName
				dependenciesToClean.remove(beanName);
				// 如果依赖 bean 集合没有任何元素了
				if (dependenciesToClean.isEmpty()) {
					// 将整个映射关系都删除
					it.remove();
				}
			}
		}

		/**
		 * 删除销毁的 bean 准备的依赖的依赖项信息
		 * 从在相关的 Bean 名称之键映射：bean 名称 bean 依赖项的 Bean 名称集删除 beanName 的映射关系
		 */
		// Remove destroyed bean's prepared dependency information.
		this.dependenciesForBeanMap.remove(beanName);
	}

	/**
	 * 将单例互斥暴露给子类和外部协作者
	 * 如果子类执行任何扩展的单例创建阶段,则它们应在给定 Object 上同步.特别是,子类不应在单例创建中涉及其自己的互斥体,以避免在惰性初始化情况下出现死锁的可能性
	 *
	 * Exposes the singleton mutex to subclasses and external collaborators.
	 * <p>Subclasses should synchronize on the given Object if they perform
	 * any sort of extended singleton creation phase. In particular, subclasses
	 * should <i>not</i> have their own mutexes involved in singleton creation,
	 * to avoid the potential for deadlocks in lazy-init situations.
	 */
	@Override
	public final Object getSingletonMutex() {
		return this.singletonObjects;
	}

}
