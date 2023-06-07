/*
 * Copyright 2002-2021 the original author or authors.
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

package org.springframework.context.support;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.AbstractBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.MergedBeanDefinitionPostProcessor;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.core.OrderComparator;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;
import org.springframework.core.metrics.ApplicationStartup;
import org.springframework.core.metrics.StartupStep;
import org.springframework.lang.Nullable;

/**
 * Delegate for AbstractApplicationContext's post-processor handling.
 *
 * @author Juergen Hoeller
 * @author Sam Brannen
 * @since 4.0
 */
final class PostProcessorRegistrationDelegate {

	private PostProcessorRegistrationDelegate() {
	}


	public static void invokeBeanFactoryPostProcessors(
			ConfigurableListableBeanFactory beanFactory, List<BeanFactoryPostProcessor> beanFactoryPostProcessors) {

		// WARNING: Although it may appear that the body of this method can be easily
		// refactored to avoid the use of multiple loops and multiple lists, the use
		// of multiple lists and multiple passes over the names of processors is
		// intentional. We must ensure that we honor the contracts for PriorityOrdered
		// and Ordered processors. Specifically, we must NOT cause processors to be
		// instantiated (via getBean() invocations) or registered in the ApplicationContext
		// in the wrong order.
		//
		// Before submitting a pull request (PR) to change this method, please review the
		// list of all declined PRs involving changes to PostProcessorRegistrationDelegate
		// to ensure that your proposal does not result in a breaking change:
		// https://github.com/spring-projects/spring-framework/issues?q=PostProcessorRegistrationDelegate+is%3Aclosed+label%3A%22status%3A+declined%22

		// Invoke BeanDefinitionRegistryPostProcessors first, if any.
		/**
		 * 无论是什么情况，优先执行 BeanDefinitionRegistryPostProcessors
		 * 将已经执行过的 BFPP 存储在 processedBeans 中，防止重复执行
		 */
		Set<String> processedBeans = new HashSet<>();

		// 判断 beanfactory 是否是 BeanDefinitionRegistry 类型，此处是 DefaultListableBeanFactory，实现了 BeanDefinitionRegistry 接口，所以为t rue
		if (beanFactory instanceof BeanDefinitionRegistry) {
			// 类型转换
			BeanDefinitionRegistry registry = (BeanDefinitionRegistry) beanFactory;
			/**
			 * 此处希望大家做一个区分，两个接口是不同的，BeanDefinitionRegistryPostProcessor是 BeanFactoryPostProcessor 的子集
			 * BeanFactoryPostProcessor主要针对的操作对象是 BeanFactory，而 BeanDefinitionRegistryPostProcessor 主要针对的操作对象是 BeanDefinition
			 * 存放 BeanFactoryPostProcessor 的集合
			 */
			List<BeanFactoryPostProcessor> regularPostProcessors = new ArrayList<>();
			// 存放 BeanDefinitionRegistryPostProcessor 的集合
			List<BeanDefinitionRegistryPostProcessor> registryProcessors = new ArrayList<>();

			/**
			 * 首先处理入参中的 beanFactoryPostProcessors ，遍历所有的 beanFactoryPostProcessors，将 BeanDefinitionRegistryPostProcessor
			 * 和 BeanFactoryPostProcessor 区分开
			 */
			for (BeanFactoryPostProcessor postProcessor : beanFactoryPostProcessors) {
				if (postProcessor instanceof BeanDefinitionRegistryPostProcessor) {
					// 如果是 BeanDefinitionRegistryPostProcessor
					BeanDefinitionRegistryPostProcessor registryProcessor =
							(BeanDefinitionRegistryPostProcessor) postProcessor;
					// 直接执行 BeanDefinitionRegistryPostProcessor 接口中的 postProcessBeanDefinitionRegistry 方法
					registryProcessor.postProcessBeanDefinitionRegistry(registry);
					// 添加到 registryProcessors，用于后续执行 postProcessBeanFactory 方法
					registryProcessors.add(registryProcessor);
				}
				else {
					// 否则，只是普通的 BeanFactoryPostProcessor，添加到 regularPostProcessors，用于后续执行 postProcessBeanFactory 方法
					regularPostProcessors.add(postProcessor);
				}
			}

			// Do not initialize FactoryBeans here: We need to leave all regular beans
			// uninitialized to let the bean factory post-processors apply to them!
			// Separate between BeanDefinitionRegistryPostProcessors that implement
			// PriorityOrdered, Ordered, and the rest.
			// 用于保存本次要执行的 BeanDefinitionRegistryPostProcessor
			List<BeanDefinitionRegistryPostProcessor> currentRegistryProcessors = new ArrayList<>();

			// First, invoke the BeanDefinitionRegistryPostProcessors that implement PriorityOrdered.
			/**
			 * 调用所有实现 PriorityOrdered 接口的 BeanDefinitionRegistryPostProcessor 实现类
			 * 找到所有实现 BeanDefinitionRegistryPostProcessor 接口 bean 的 beanName
			 */
			String[] postProcessorNames =
					beanFactory.getBeanNamesForType(BeanDefinitionRegistryPostProcessor.class, true, false);
			// 遍历处理所有符合规则的 postProcessorNames
			for (String ppName : postProcessorNames) {
				// 检测是否实现了 PriorityOrdered 接口
				if (beanFactory.isTypeMatch(ppName, PriorityOrdered.class)) {
					// 获取名字对应的 bean 实例，添加到 currentRegistryProcessors 中
					currentRegistryProcessors.add(beanFactory.getBean(ppName, BeanDefinitionRegistryPostProcessor.class));
					// 将要被执行的 BFPP 名称添加到 processedBeans，避免后续重复执行
					processedBeans.add(ppName);
				}
			}
			// 按照优先级进行排序操作
			sortPostProcessors(currentRegistryProcessors, beanFactory);
			// 添加到 registryProcessors 中，用于最后执行 postProcessBeanFactory 方法
			registryProcessors.addAll(currentRegistryProcessors);
			// 遍历 currentRegistryProcessors，执行 postProcessBeanDefinitionRegistry 方法
			invokeBeanDefinitionRegistryPostProcessors(currentRegistryProcessors, registry, beanFactory.getApplicationStartup());
			// 执行完毕之后，清空 currentRegistryProcessors
			currentRegistryProcessors.clear();

			// Next, invoke the BeanDefinitionRegistryPostProcessors that implement Ordered.
			/**
			 * 调用所有实现 Ordered 接口的 BeanDefinitionRegistryPostProcessor实现类
			 * 找到所有实现 BeanDefinitionRegistryPostProcessor 接口 bean 的 beanName
			 * 此处需要重复查找的原因在于上面的执行过程中可能会新增其他的 BeanDefinitionRegistryPostProcessor
			 */
			postProcessorNames = beanFactory.getBeanNamesForType(BeanDefinitionRegistryPostProcessor.class, true, false);
			for (String ppName : postProcessorNames) {
				// 检测是否实现了 Ordered 接口，并且还未执行过
				if (!processedBeans.contains(ppName) && beanFactory.isTypeMatch(ppName, Ordered.class)) {
					// 获取名字对应的 bean 实例，添加到 currentRegistryProcessors 中
					currentRegistryProcessors.add(beanFactory.getBean(ppName, BeanDefinitionRegistryPostProcessor.class));
					// 将要被执行的 BFPP 名称添加到 processedBeans，避免后续重复执行
					processedBeans.add(ppName);
				}
			}
			// 按照优先级进行排序操作
			sortPostProcessors(currentRegistryProcessors, beanFactory);
			// 添加到 registryProcessors 中，用于最后执行 postProcessBeanFactory 方法
			registryProcessors.addAll(currentRegistryProcessors);
			// 遍历 currentRegistryProcessors，执行 postProcessBeanDefinitionRegistry 方法
			invokeBeanDefinitionRegistryPostProcessors(currentRegistryProcessors, registry, beanFactory.getApplicationStartup());
			// 执行完毕之后，清空 currentRegistryProcessors
			currentRegistryProcessors.clear();

			// Finally, invoke all other BeanDefinitionRegistryPostProcessors until no further ones appear.
			// 最后，调用所有剩下的 BeanDefinitionRegistryPostProcessors
			boolean reiterate = true;
			while (reiterate) {
				reiterate = false;
				// 找出所有实现 BeanDefinitionRegistryPostProcessor 接口的类
				postProcessorNames = beanFactory.getBeanNamesForType(BeanDefinitionRegistryPostProcessor.class, true, false);
				// 遍历执行
				for (String ppName : postProcessorNames) {
					// 跳过已经执行过的 BeanDefinitionRegistryPostProcessor
					if (!processedBeans.contains(ppName)) {
						// 获取名字对应的 bean 实例，添加到 currentRegistryProcessors 中
						currentRegistryProcessors.add(beanFactory.getBean(ppName, BeanDefinitionRegistryPostProcessor.class));
						// 将要被执行的 BFPP 名称添加到 processedBeans，避免后续重复执行
						processedBeans.add(ppName);
						reiterate = true;
					}
				}
				// 按照优先级进行排序操作
				sortPostProcessors(currentRegistryProcessors, beanFactory);
				// 添加到 registryProcessors 中，用于最后执行 postProcessBeanFactory 方法
				registryProcessors.addAll(currentRegistryProcessors);
				// 遍历 currentRegistryProcessors，执行 postProcessBeanDefinitionRegistry 方法
				invokeBeanDefinitionRegistryPostProcessors(currentRegistryProcessors, registry, beanFactory.getApplicationStartup());
				// 执行完毕之后，清空 currentRegistryProcessors
				currentRegistryProcessors.clear();
			}

			// Now, invoke the postProcessBeanFactory callback of all processors handled so far.
			// 调用所有 BeanDefinitionRegistryPostProcessor 的 postProcessBeanFactory 方法
			invokeBeanFactoryPostProcessors(registryProcessors, beanFactory);
			// 最后，调用入参 beanFactoryPostProcessors 中的普通 BeanFactoryPostProcessor 的 postProcessBeanFactory 方法
			invokeBeanFactoryPostProcessors(regularPostProcessors, beanFactory);
		}

		else {
			// Invoke factory processors registered with the context instance.
			// 如果 beanFactory 不归属于 BeanDefinitionRegistry 类型，那么直接执行 postProcessBeanFactory 方法
			invokeBeanFactoryPostProcessors(beanFactoryPostProcessors, beanFactory);
		}

		/**
		 * 到这里为止，入参 beanFactoryPostProcessors 和容器中的所有 BeanDefinitionRegistryPostProcessor 已经全部处理完毕，下面开始处理容器中
		 * 所有的 BeanFactoryPostProcessor
		 * 可能会包含一些实现类，只实现了 BeanFactoryPostProcessor，并没有实现 BeanDefinitionRegistryPostProcessor 接口
		 */

		// Do not initialize FactoryBeans here: We need to leave all regular beans
		// uninitialized to let the bean factory post-processors apply to them!
		// 找到所有实现 BeanFactoryPostProcessor 接口的类
		String[] postProcessorNames =
				beanFactory.getBeanNamesForType(BeanFactoryPostProcessor.class, true, false);

		// Separate between BeanFactoryPostProcessors that implement PriorityOrdered,
		// Ordered, and the rest.
		// 用于存放实现了 PriorityOrdered 接口的 BeanFactoryPostProcessor
		List<BeanFactoryPostProcessor> priorityOrderedPostProcessors = new ArrayList<>();
		// 用于存放实现了 Ordered 接口的 BeanFactoryPostProcessor 的 beanName
		List<String> orderedPostProcessorNames = new ArrayList<>();
		// 用于存放普通 BeanFactoryPostProcessor的beanName
		List<String> nonOrderedPostProcessorNames = new ArrayList<>();
		// 遍历 postProcessorNames，将 BeanFactoryPostProcessor 按实现 PriorityOrdered、实现 Ordered 接口、普通三种区分开
		for (String ppName : postProcessorNames) {
			// 跳过已经执行过的 BeanFactoryPostProcessor
			if (processedBeans.contains(ppName)) {
				// skip - already processed in first phase above
			}
			// 添加实现了 PriorityOrdered 接口的 BeanFactoryPostProcessor 到 priorityOrderedPostProcessors
			else if (beanFactory.isTypeMatch(ppName, PriorityOrdered.class)) {
				priorityOrderedPostProcessors.add(beanFactory.getBean(ppName, BeanFactoryPostProcessor.class));
			}
			// 添加实现了 Ordered 接口的 BeanFactoryPostProcessor 的 beanName 到 orderedPostProcessorNames
			else if (beanFactory.isTypeMatch(ppName, Ordered.class)) {
				orderedPostProcessorNames.add(ppName);
			}
			else {
				// 添加剩下的普通 BeanFactoryPostProcessor 的 beanName 到 nonOrderedPostProcessorNames
				nonOrderedPostProcessorNames.add(ppName);
			}
		}

		// First, invoke the BeanFactoryPostProcessors that implement PriorityOrdered.
		// 对实现了 PriorityOrdered 接口的 BeanFactoryPostProcessor 进行排序
		sortPostProcessors(priorityOrderedPostProcessors, beanFactory);
		// 遍历实现了 PriorityOrdered 接口的 BeanFactoryPostProcessor，执行 postProcessBeanFactory 方法
		invokeBeanFactoryPostProcessors(priorityOrderedPostProcessors, beanFactory);

		// Next, invoke the BeanFactoryPostProcessors that implement Ordered.
		// 创建存放实现了 Ordered 接口的 BeanFactoryPostProcessor 集合
		List<BeanFactoryPostProcessor> orderedPostProcessors = new ArrayList<>(orderedPostProcessorNames.size());
		// 遍历存放实现了 Ordered 接口的 BeanFactoryPostProcessor 名字的集合
		for (String postProcessorName : orderedPostProcessorNames) {
			// 将实现了 Ordered 接口的 BeanFactoryPostProcessor 添加到集合中
			orderedPostProcessors.add(beanFactory.getBean(postProcessorName, BeanFactoryPostProcessor.class));
		}
		// 对实现了 Ordered 接口的 BeanFactoryPostProcessor 进行排序操作
		sortPostProcessors(orderedPostProcessors, beanFactory);
		// 遍历实现了 Ordered 接口的 BeanFactoryPostProcessor，执行 postProcessBeanFactory 方法
		invokeBeanFactoryPostProcessors(orderedPostProcessors, beanFactory);

		// Finally, invoke all other BeanFactoryPostProcessors.
		// 最后，创建存放普通的 BeanFactoryPostProcessor 的集合
		List<BeanFactoryPostProcessor> nonOrderedPostProcessors = new ArrayList<>(nonOrderedPostProcessorNames.size());
		// 遍历存放实现了普通 BeanFactoryPostProcessor 名字的集合
		for (String postProcessorName : nonOrderedPostProcessorNames) {
			// 将普通的 BeanFactoryPostProcessor 添加到集合中
			nonOrderedPostProcessors.add(beanFactory.getBean(postProcessorName, BeanFactoryPostProcessor.class));
		}
		// 遍历普通的 BeanFactoryPostProcessor，执行 postProcessBeanFactory 方法
		invokeBeanFactoryPostProcessors(nonOrderedPostProcessors, beanFactory);

		// Clear cached merged bean definitions since the post-processors might have
		// modified the original metadata, e.g. replacing placeholders in values...
		/**
		 * 清除元数据缓存（mergeBeanDefinitions、allBeanNamesByType、singletonBeanNameByType）
		 * 因为后置处理器可能已经修改了原始元数据，例如，替换值中的占位符
		 */
		beanFactory.clearMetadataCache();
	}

	/**
	 * 注册beanPostProcessor
	 * @param beanFactory
	 * @param applicationContext
	 */
	public static void registerBeanPostProcessors(
			ConfigurableListableBeanFactory beanFactory, AbstractApplicationContext applicationContext) {

		// WARNING: Although it may appear that the body of this method can be easily
		// refactored to avoid the use of multiple loops and multiple lists, the use
		// of multiple lists and multiple passes over the names of processors is
		// intentional. We must ensure that we honor the contracts for PriorityOrdered
		// and Ordered processors. Specifically, we must NOT cause processors to be
		// instantiated (via getBean() invocations) or registered in the ApplicationContext
		// in the wrong order.
		//
		// Before submitting a pull request (PR) to change this method, please review the
		// list of all declined PRs involving changes to PostProcessorRegistrationDelegate
		// to ensure that your proposal does not result in a breaking change:
		// https://github.com/spring-projects/spring-framework/issues?q=PostProcessorRegistrationDelegate+is%3Aclosed+label%3A%22status%3A+declined%22

		// 找到所有实现了 BeanPostProcessor 接口的类
		String[] postProcessorNames = beanFactory.getBeanNamesForType(BeanPostProcessor.class, true, false);

		// Register BeanPostProcessorChecker that logs an info message when
		// a bean is created during BeanPostProcessor instantiation, i.e. when
		// a bean is not eligible for getting processed by all BeanPostProcessors.
		/**
		 * 记录下 BeanPostProcessor 的目标计数
		 * 此处为什么要+1呢，原因非常简单，在此方法的最后会添加一个 BeanPostProcessorChecker 的类
		 */
		int beanProcessorTargetCount = beanFactory.getBeanPostProcessorCount() + 1 + postProcessorNames.length;
		// 添加 BeanPostProcessorChecker (主要用于记录信息)到 beanFactory 中
		beanFactory.addBeanPostProcessor(new BeanPostProcessorChecker(beanFactory, beanProcessorTargetCount));

		// Separate between BeanPostProcessors that implement PriorityOrdered,
		// Ordered, and the rest.
		// 定义存放实现了 PriorityOrdered 接口的 BeanPostProcessor 集合
		List<BeanPostProcessor> priorityOrderedPostProcessors = new ArrayList<>();
		// 定义存放 spring 内部的 BeanPostProcessor
		List<BeanPostProcessor> internalPostProcessors = new ArrayList<>();
		// 定义存放实现了 Ordered 接口的 BeanPostProcessor 的 name 集合
		List<String> orderedPostProcessorNames = new ArrayList<>();
		// 定义存放普通的 BeanPostProcessor 的 name 集合
		List<String> nonOrderedPostProcessorNames = new ArrayList<>();
		// 遍历 beanFactory 中存在的 BeanPostProcessor 的集合 postProcessorNames，
		for (String ppName : postProcessorNames) {
			// 如果 ppName 对应的 BeanPostProcessor 实例实现了 PriorityOrdered 接口，则获取到 ppName 对应的 BeanPostProcessor 的实例添加到 priorityOrderedPostProcessors 中
			if (beanFactory.isTypeMatch(ppName, PriorityOrdered.class)) {
				BeanPostProcessor pp = beanFactory.getBean(ppName, BeanPostProcessor.class);
				priorityOrderedPostProcessors.add(pp);
				// 如果 ppName 对应的 BeanPostProcessor 实例也实现了 MergedBeanDefinitionPostProcessor 接口，那么则将 ppName 对应的 bean 实例添加到 internalPostProcessors 中
				if (pp instanceof MergedBeanDefinitionPostProcessor) {
					internalPostProcessors.add(pp);
				}
			}
			// 如果 ppName 对应的 BeanPostProcessor 实例没有实现 PriorityOrdered 接口，但是实现了 Ordered 接口，那么将 ppName 对应的 bean 实例添加到 orderedPostProcessorNames 中
			else if (beanFactory.isTypeMatch(ppName, Ordered.class)) {
				orderedPostProcessorNames.add(ppName);
			}
			else {
				// 否则将 ppName 添加到 nonOrderedPostProcessorNames 中
				nonOrderedPostProcessorNames.add(ppName);
			}
		}

		// First, register the BeanPostProcessors that implement PriorityOrdered.
		// 首先，对实现了 PriorityOrdered 接口的 BeanPostProcessor 实例进行排序操作
		sortPostProcessors(priorityOrderedPostProcessors, beanFactory);
		// 注册实现了 PriorityOrdered 接口的 BeanPostProcessor 实例添加到 beanFactory 中
		registerBeanPostProcessors(beanFactory, priorityOrderedPostProcessors);

		// Next, register the BeanPostProcessors that implement Ordered.
		// 注册所有实现 Ordered 的 beanPostProcessor
		List<BeanPostProcessor> orderedPostProcessors = new ArrayList<>(orderedPostProcessorNames.size());
		for (String ppName : orderedPostProcessorNames) {
			// 根据 ppName 找到对应的 BeanPostProcessor 实例对象
			BeanPostProcessor pp = beanFactory.getBean(ppName, BeanPostProcessor.class);
			// 将实现了 Ordered 接口的 BeanPostProcessor 添加到 orderedPostProcessors 集合中
			orderedPostProcessors.add(pp);
			// 如果 ppName 对应的 BeanPostProcessor 实例也实现了 MergedBeanDefinitionPostProcessor 接口，那么则将 ppName 对应的 bean 实例添加到 internalPostProcessors 中
			if (pp instanceof MergedBeanDefinitionPostProcessor) {
				internalPostProcessors.add(pp);
			}
		}
		// 对实现了 Ordered 接口的 BeanPostProcessor 进行排序操作
		sortPostProcessors(orderedPostProcessors, beanFactory);
		// 注册实现了 Ordered 接口的 BeanPostProcessor 实例添加到 beanFactory 中
		registerBeanPostProcessors(beanFactory, orderedPostProcessors);

		// Now, register all regular BeanPostProcessors.
		// 创建存放没有实现 PriorityOrdered 和 Ordered 接口的 BeanPostProcessor 的集合
		List<BeanPostProcessor> nonOrderedPostProcessors = new ArrayList<>(nonOrderedPostProcessorNames.size());
		// 遍历集合
		for (String ppName : nonOrderedPostProcessorNames) {
			// 根据 ppName 找到对应的 BeanPostProcessor 实例对象
			BeanPostProcessor pp = beanFactory.getBean(ppName, BeanPostProcessor.class);
			// 将没有实现 PriorityOrdered 和 Ordered 接口的 BeanPostProcessor 添加到 nonOrderedPostProcessors 集合中
			nonOrderedPostProcessors.add(pp);
			// 如果 ppName 对应的 BeanPostProcessor 实例也实现了 MergedBeanDefinitionPostProcessor 接口，那么则将 ppName 对应的 bean 实例添加到 internalPostProcessors 中
			if (pp instanceof MergedBeanDefinitionPostProcessor) {
				internalPostProcessors.add(pp);
			}
		}
		// 注册没有实现 PriorityOrdered 和 Ordered 的 BeanPostProcessor 实例添加到 beanFactory 中
		registerBeanPostProcessors(beanFactory, nonOrderedPostProcessors);

		// Finally, re-register all internal BeanPostProcessors.
		// 将所有实现了 MergedBeanDefinitionPostProcessor 类型的 BeanPostProcessor 进行排序操作
		sortPostProcessors(internalPostProcessors, beanFactory);
		// 注册所有实现了 MergedBeanDefinitionPostProcessor 类型的 BeanPostProcessor 到 beanFactory 中
		registerBeanPostProcessors(beanFactory, internalPostProcessors);

		// Re-register post-processor for detecting inner beans as ApplicationListeners,
		// moving it to the end of the processor chain (for picking up proxies etc).
		// 注册 ApplicationListenerDetector 到 beanFactory 中
		beanFactory.addBeanPostProcessor(new ApplicationListenerDetector(applicationContext));
	}

	private static void sortPostProcessors(List<?> postProcessors, ConfigurableListableBeanFactory beanFactory) {
		// Nothing to sort?
		if (postProcessors.size() <= 1) {
			return;
		}
		Comparator<Object> comparatorToUse = null;
		if (beanFactory instanceof DefaultListableBeanFactory) {
			comparatorToUse = ((DefaultListableBeanFactory) beanFactory).getDependencyComparator();
		}
		if (comparatorToUse == null) {
			comparatorToUse = OrderComparator.INSTANCE;
		}
		postProcessors.sort(comparatorToUse);
	}

	/**
	 * Invoke the given BeanDefinitionRegistryPostProcessor beans.
	 */
	private static void invokeBeanDefinitionRegistryPostProcessors(
			Collection<? extends BeanDefinitionRegistryPostProcessor> postProcessors, BeanDefinitionRegistry registry, ApplicationStartup applicationStartup) {

		for (BeanDefinitionRegistryPostProcessor postProcessor : postProcessors) {
			StartupStep postProcessBeanDefRegistry = applicationStartup.start("spring.context.beandef-registry.post-process")
					.tag("postProcessor", postProcessor::toString);
			postProcessor.postProcessBeanDefinitionRegistry(registry);
			postProcessBeanDefRegistry.end();
		}
	}

	/**
	 * Invoke the given BeanFactoryPostProcessor beans.
	 */
	private static void invokeBeanFactoryPostProcessors(
			Collection<? extends BeanFactoryPostProcessor> postProcessors, ConfigurableListableBeanFactory beanFactory) {

		for (BeanFactoryPostProcessor postProcessor : postProcessors) {
			StartupStep postProcessBeanFactory = beanFactory.getApplicationStartup().start("spring.context.bean-factory.post-process")
					.tag("postProcessor", postProcessor::toString);
			postProcessor.postProcessBeanFactory(beanFactory);
			postProcessBeanFactory.end();
		}
	}

	/**
	 * Register the given BeanPostProcessor beans.
	 */
	private static void registerBeanPostProcessors(
			ConfigurableListableBeanFactory beanFactory, List<BeanPostProcessor> postProcessors) {

		if (beanFactory instanceof AbstractBeanFactory) {
			// Bulk addition is more efficient against our CopyOnWriteArrayList there
			((AbstractBeanFactory) beanFactory).addBeanPostProcessors(postProcessors);
		}
		else {
			for (BeanPostProcessor postProcessor : postProcessors) {
				beanFactory.addBeanPostProcessor(postProcessor);
			}
		}
	}


	/**
	 * BeanPostProcessor that logs an info message when a bean is created during
	 * BeanPostProcessor instantiation, i.e. when a bean is not eligible for
	 * getting processed by all BeanPostProcessors.
	 */
	private static final class BeanPostProcessorChecker implements BeanPostProcessor {

		private static final Log logger = LogFactory.getLog(BeanPostProcessorChecker.class);

		private final ConfigurableListableBeanFactory beanFactory;

		private final int beanPostProcessorTargetCount;

		public BeanPostProcessorChecker(ConfigurableListableBeanFactory beanFactory, int beanPostProcessorTargetCount) {
			this.beanFactory = beanFactory;
			this.beanPostProcessorTargetCount = beanPostProcessorTargetCount;
		}

		@Override
		public Object postProcessBeforeInitialization(Object bean, String beanName) {
			return bean;
		}

		@Override
		public Object postProcessAfterInitialization(Object bean, String beanName) {
			if (!(bean instanceof BeanPostProcessor) && !isInfrastructureBean(beanName) &&
					this.beanFactory.getBeanPostProcessorCount() < this.beanPostProcessorTargetCount) {
				if (logger.isInfoEnabled()) {
					logger.info("Bean '" + beanName + "' of type [" + bean.getClass().getName() +
							"] is not eligible for getting processed by all BeanPostProcessors " +
							"(for example: not eligible for auto-proxying)");
				}
			}
			return bean;
		}

		private boolean isInfrastructureBean(@Nullable String beanName) {
			if (beanName != null && this.beanFactory.containsBeanDefinition(beanName)) {
				BeanDefinition bd = this.beanFactory.getBeanDefinition(beanName);
				return (bd.getRole() == RootBeanDefinition.ROLE_INFRASTRUCTURE);
			}
			return false;
		}
	}

}
