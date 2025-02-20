/*
 * Copyright (C) 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.tools.res;

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.android.annotations.concurrency.GuardedBy;
import com.android.ide.common.rendering.api.ResourceNamespace;
import com.android.ide.common.resources.ResourceItem;
import com.android.ide.common.resources.ResourceRepository;
import com.android.ide.common.resources.ResourceTable;
import com.android.ide.common.resources.ResourceVisitor;
import com.android.ide.common.resources.SingleNamespaceResourceRepository;
import com.android.ide.common.resources.configuration.FolderConfiguration;
import com.android.resources.ResourceType;
import com.android.resources.aar.AarResourceRepository;
import com.android.tools.environment.Logger;
import com.google.common.base.Stopwatch;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multiset;
import com.google.common.collect.Table;
import com.google.common.collect.Tables;
import com.google.common.util.concurrent.MoreExecutors;
import com.intellij.util.SmartList;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import java.util.AbstractCollection;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import org.jetbrains.annotations.VisibleForTesting;

/**
 * A super class for several of the other repositories. Its only purpose is to be able to combine
 * multiple resource repositories and expose it as a single one, applying the “override” semantics
 * of resources: earlier children defining the same resource namespace/type/name combination will
 * replace/hide any subsequent definitions of the same resource.
 *
 * <p>In the resource repository hierarchy, MultiResourceRepository is an internal node, never a leaf.
 */
@SuppressWarnings("InstanceGuardedByStatic") // TODO: The whole locking scheme for resource repositories needs to be reworked.
public abstract class MultiResourceRepository<T> extends LocalResourceRepository<T> {
  private static final Logger LOG = Logger.getInstance(MultiResourceRepository.class);

  @GuardedBy("ITEM_MAP_LOCK")
  @NonNull private ImmutableList<LocalResourceRepository<T>> myLocalResources = ImmutableList.of();
  /** A concatenation of {@link #myLocalResources} and library resources. */
  @GuardedBy("ITEM_MAP_LOCK")
  @NonNull private ImmutableList<ResourceRepository> myChildren = ImmutableList.of();
  /** Leaf resource repositories keyed by namespace. */
  @GuardedBy("ITEM_MAP_LOCK")
  @NonNull private ImmutableListMultimap<ResourceNamespace, SingleNamespaceResourceRepository> myLeafsByNamespace =
      ImmutableListMultimap.of();
  /** Contained single-namespace resource repositories keyed by namespace. */
  @GuardedBy("ITEM_MAP_LOCK")
  @NonNull private ImmutableListMultimap<ResourceNamespace, SingleNamespaceResourceRepository> myRepositoriesByNamespace =
      ImmutableListMultimap.of();

  @GuardedBy("ITEM_MAP_LOCK")
  @NonNull private ResourceItemComparator myResourceComparator =
      new ResourceItemComparator(new ResourcePriorityComparator(ImmutableList.of()));

  @GuardedBy("ITEM_MAP_LOCK")
  private long[] myModificationCounts;

  @GuardedBy("ITEM_MAP_LOCK")
  private final ResourceTable myCachedMaps = new ResourceTable();

  /** Names of resources from local leaf repositories. */
  @GuardedBy("ITEM_MAP_LOCK")
  private final Table<SingleNamespaceResourceRepository, ResourceType, Set<String>> myResourceNames =
      Tables.newCustomTable(new HashMap<>(), () -> Maps.newEnumMap(ResourceType.class));

  /** Describes groups of resources that are out of date in {@link #myCachedMaps}. */
  @GuardedBy("ITEM_MAP_LOCK")
  private final Table<ResourceNamespace, ResourceType, Set<SingleNamespaceResourceRepository>> myUnreconciledResources =
      Tables.newCustomTable(new HashMap<>(), () -> Maps.newEnumMap(ResourceType.class));

  protected MultiResourceRepository(@NonNull String displayName) {
    super(displayName);
  }

  protected void setChildren(@NonNull List<? extends LocalResourceRepository<T>> localResources,
                             @NonNull Collection<? extends AarResourceRepository> libraryResources,
                             @NonNull Collection<? extends ResourceRepository> otherResources) {
    synchronized (ITEM_MAP_LOCK) {
      release();
      setModificationCount(ourModificationCounter.incrementAndGet());
      myLocalResources = ImmutableList.copyOf(localResources);
      int size = myLocalResources.size() + libraryResources.size() + otherResources.size();
      myChildren = ImmutableList.<ResourceRepository>builderWithExpectedSize(size)
          .addAll(myLocalResources).addAll(libraryResources).addAll(otherResources).build();

      ImmutableListMultimap.Builder<ResourceNamespace, SingleNamespaceResourceRepository> mapBuilder = ImmutableListMultimap.builder();
      computeLeafs(this, mapBuilder);
      myLeafsByNamespace = mapBuilder.build();

      mapBuilder = ImmutableListMultimap.builder();
      computeNamespaceMap(this, mapBuilder);
      myRepositoriesByNamespace = mapBuilder.build();

      myResourceComparator = new ResourceItemComparator(new ResourcePriorityComparator(myLeafsByNamespace.values()));

      myModificationCounts = new long[localResources.size()];
      if (localResources.size() == 1) {
        // Make sure that the modification count of the child and the parent are same. This is
        // done so that we can return child's modification count, instead of ours.
        LocalResourceRepository<T> child = localResources.get(0);
        child.setModificationCount(getModificationCount());
      }
      int i = 0;
      for (LocalResourceRepository<T> child : myLocalResources) {
        child.addParent(this);
        myModificationCounts[i++] = child.getModificationCount();
      }
      myCachedMaps.clear();

      invalidateParentCaches();
    }
  }

  abstract protected void refreshChildren();

  public void onChildReset() {
    refreshChildren();
  }

  @GuardedBy("ITEM_MAP_LOCK")
  private static <T> void computeLeafs(@NonNull ResourceRepository repository,
                                   @NonNull ImmutableListMultimap.Builder<ResourceNamespace, SingleNamespaceResourceRepository> result) {
    if (repository instanceof MultiResourceRepository) {
      for (ResourceRepository child : ((MultiResourceRepository<T>)repository).myChildren) {
        computeLeafs(child, result);
      }
    } else {
      for (SingleNamespaceResourceRepository resourceRepository : repository.getLeafResourceRepositories()) {
        result.put(resourceRepository.getNamespace(), resourceRepository);
      }
    }
  }

  @GuardedBy("ITEM_MAP_LOCK")
  private static <T> void computeNamespaceMap(
      @NonNull ResourceRepository repository,
      @NonNull ImmutableListMultimap.Builder<ResourceNamespace, SingleNamespaceResourceRepository> result) {
    if (repository instanceof SingleNamespaceResourceRepository) {
      SingleNamespaceResourceRepository singleNamespaceRepository = (SingleNamespaceResourceRepository)repository;
      ResourceNamespace namespace = singleNamespaceRepository.getNamespace();
      result.put(namespace, singleNamespaceRepository);
    }
    else if (repository instanceof MultiResourceRepository) {
      for (ResourceRepository child : ((MultiResourceRepository<T>)repository).myChildren) {
        computeNamespaceMap(child, result);
      }
    }
  }

  public ImmutableList<LocalResourceRepository<T>> getLocalResources() {
    synchronized (ITEM_MAP_LOCK) {
      return myLocalResources;
    }
  }

  @NonNull
  public final List<ResourceRepository> getChildren() {
    synchronized (ITEM_MAP_LOCK) {
      return myChildren;
    }
  }

  /**
   * Returns resource repositories for the given namespace. In case of nested single-namespace repositories only the outermost
   * repositories are returned. Collectively the returned repositories are guaranteed to contain all resources in the given namespace
   * contained in this repository.
   *
   * @param namespace the namespace to return resource repositories for
   * @return a list of namespaces for the given namespace
   */
  @NonNull
  public final List<SingleNamespaceResourceRepository> getRepositoriesForNamespace(@NonNull ResourceNamespace namespace) {
    synchronized (ITEM_MAP_LOCK) {
      return myRepositoriesByNamespace.get(namespace);
    }
  }

  @Override
  public long getModificationCount() {
    synchronized (ITEM_MAP_LOCK) {
      if (myLocalResources.size() == 1) {
        return myLocalResources.get(0).getModificationCount();
      }

      // See if any of the delegates have changed.
      boolean changed = false;
      for (int i = 0; i < myLocalResources.size(); i++) {
        LocalResourceRepository<T> child = myLocalResources.get(i);
        long rev = child.getModificationCount();
        if (rev != myModificationCounts[i]) {
          myModificationCounts[i] = rev;
          changed = true;
        }
      }

      if (changed) {
        setModificationCount(ourModificationCounter.incrementAndGet());
      }

      return super.getModificationCount();
    }
  }

  @Override
  @NonNull
  public Set<ResourceNamespace> getNamespaces() {
    synchronized (ITEM_MAP_LOCK) {
      return myRepositoriesByNamespace.keySet();
    }
  }

  @Override
  @NonNull
  public ResourceVisitor.VisitResult accept(@NonNull ResourceVisitor visitor) {
    synchronized (ITEM_MAP_LOCK) {
      for (ResourceNamespace namespace : getNamespaces()) {
        if (visitor.shouldVisitNamespace(namespace)) {
          for (ResourceType type : ResourceType.values()) {
            if (visitor.shouldVisitResourceType(type)) {
              ListMultimap<String, ResourceItem> map = getMap(namespace, type);
              if (map != null) {
                for (ResourceItem item : map.values()) {
                  if (visitor.visit(item) == ResourceVisitor.VisitResult.ABORT) {
                    return ResourceVisitor.VisitResult.ABORT;
                  }
                }
              }
            }
          }
        }
      }
    }

    return ResourceVisitor.VisitResult.CONTINUE;
  }

  @GuardedBy("ITEM_MAP_LOCK")
  @Override
  @Nullable
  protected ListMultimap<String, ResourceItem> getMap(@NonNull ResourceNamespace namespace, @NonNull ResourceType type) {
    ImmutableList<SingleNamespaceResourceRepository> repositoriesForNamespace = myLeafsByNamespace.get(namespace);
    if (repositoriesForNamespace.size() == 1) {
      SingleNamespaceResourceRepository repository = repositoriesForNamespace.get(0);
      return getResourcesUnderLock(repository, namespace, type);
    }

    ListMultimap<String, ResourceItem> map = myCachedMaps.get(namespace, type);
    Set<SingleNamespaceResourceRepository> unreconciledRepositories = null;
    if (map != null) {
      unreconciledRepositories = myUnreconciledResources.get(namespace, type);
      if (unreconciledRepositories == null) {
        return map;
      }
    }

    // Merge all items of the given type.
    Stopwatch stopwatch = LOG.isDebugEnabled() ? Stopwatch.createStarted() : null;

    if (map == null) {
      for (SingleNamespaceResourceRepository repository : repositoriesForNamespace) {
        ListMultimap<String, ResourceItem> items = getResourcesUnderLock(repository, namespace, type);
        if (!items.isEmpty()) {
          if (map == null) {
            // Create a new map.
            // We only add a duplicate item if there isn't an item with the same qualifiers, and it
            // is not a styleable or an id. Styleables and ids are allowed to be defined in multiple
            // places even with the same qualifiers.
            map = type == ResourceType.STYLEABLE || type == ResourceType.ID ?
                  ArrayListMultimap.create() : new PerConfigResourceMap(myResourceComparator);
            myCachedMaps.put(namespace, type, map);
          }
          map.putAll(items);

          if (repository instanceof LocalResourceRepository) {
            myResourceNames.put(repository, type, ImmutableSet.copyOf(items.keySet()));
          }
        }
      }
    }
    else {
      // Update a partially out of date map.
      for (SingleNamespaceResourceRepository unreconciledRepository : unreconciledRepositories) {
        // Delete all resources that belonged to unreconciledRepository.
        Predicate<ResourceItem> filter = item -> item.getRepository().equals(unreconciledRepository);
        Set<String> names = myResourceNames.get(unreconciledRepository, type);
        if (names != null) {
          PerConfigResourceMap perConfigMap = map instanceof PerConfigResourceMap ? (PerConfigResourceMap)map : null;
          for (String name : names) {
            if (perConfigMap != null) {
              perConfigMap.removeIf(name, filter);
            }
            else {
              List<ResourceItem> items = map.get(name);
              items.removeIf(filter);
              if (items.isEmpty()) {
                map.removeAll(name);
              }
            }
          }
        }
        // Add all resources from unreconciledRepository.
        ListMultimap<String, ResourceItem> unreconciledResources = getResourcesUnderLock(unreconciledRepository, namespace, type);
        map.putAll(unreconciledResources);

        assert unreconciledRepository instanceof LocalResourceRepository;
        myResourceNames.put(unreconciledRepository, type, ImmutableSet.copyOf(unreconciledResources.keySet()));
        if (map.isEmpty()) {
          myCachedMaps.remove(namespace, type);
        }
      }

      myUnreconciledResources.remove(namespace, type);
    }

    if (stopwatch != null) {
      LOG.debug(String.format(Locale.US,
                              "Merged %d resources of type %s in %s for %s.",
                              map == null ? 0 : map.size(),
                              type,
                              stopwatch,
                              getClass().getSimpleName()));
    }

    return map;
  }

  @GuardedBy("ITEM_MAP_LOCK")
  @NonNull
  private static <T> ListMultimap<String, ResourceItem> getResourcesUnderLock(@NonNull SingleNamespaceResourceRepository repository,
                                                                              @NonNull ResourceNamespace namespace,
                                                                              @NonNull ResourceType type) {
    ListMultimap<String, ResourceItem> map;
    if (repository instanceof LocalResourceRepository) {
      map = ((LocalResourceRepository<T>)repository).getMapPackageAccessible(namespace, type);
      return map == null ? ImmutableListMultimap.of() : map;
    }
    return repository.getResources(namespace, type);
  }

  protected final void release() {
    synchronized (ITEM_MAP_LOCK) {
      for (LocalResourceRepository<T> child : myLocalResources) {
        child.removeParent(this);
      }
    }
  }

  /**
   * Notifies this repository that all its caches are no longer valid.
   */
  @GuardedBy("ITEM_MAP_LOCK")
  public void invalidateCache() {
    clearCachedData();
    setModificationCount(ourModificationCounter.incrementAndGet());

    invalidateParentCaches();
  }

  @GuardedBy("ITEM_MAP_LOCK")
  private void clearCachedData() {
    myCachedMaps.clear();
    myResourceNames.clear();
    myUnreconciledResources.clear();
  }

  protected final void onLowMemory() {
    synchronized (ITEM_MAP_LOCK) {
      clearCachedData();
    }
    LOG.warn(getDisplayName() + ": Cached data cleared due to low memory");
  }

  /**
   * Notifies this delegating repository that the given dependent repository has invalidated
   * resources of the given types.
   */
  @GuardedBy("ITEM_MAP_LOCK")
  public void invalidateCache(@NonNull SingleNamespaceResourceRepository repository, @NonNull ResourceType... types) {
    ResourceNamespace namespace = repository.getNamespace();

    // Since myLeafsByNamespace updates are not atomic with respect to grandchildren updates, it is
    // possible that the repository that triggered cache invalidation is not in myLeafsByNamespace.
    // In such a case we don't need to do anything.
    ImmutableList<SingleNamespaceResourceRepository> leafs = myLeafsByNamespace.get(namespace);
    if (leafs.contains(repository)) {
      // Update myUnreconciledResources only if myCachedMaps is used for this namespace.
      if (leafs.size() != 1) {
        for (ResourceType type : types) {
          if (myCachedMaps.get(namespace, type) != null) {
            Set<SingleNamespaceResourceRepository> repositories = myUnreconciledResources.get(namespace, type);
            if (repositories == null) {
              repositories = new HashSet<>();
              myUnreconciledResources.put(namespace, type, repositories);
            }
            repositories.add(repository);
          }
        }

        setModificationCount(ourModificationCounter.incrementAndGet());
      }

      invalidateParentCaches(repository, types);
    }
  }

  @Override
  public void invokeAfterPendingUpdatesFinish(@NonNull Executor executor, @NonNull Runnable callback) {
    List<LocalResourceRepository<T>> repositories = getLocalResources();
    AtomicInteger count = new AtomicInteger(repositories.size());
    for (LocalResourceRepository<T> childRepository : repositories) {
      childRepository.invokeAfterPendingUpdatesFinish(MoreExecutors.directExecutor(), () -> {
        if (count.decrementAndGet() == 0) {
          executor.execute(callback);
        }
      });
    }
  }

  @Override
  @NonNull
  protected Set<T> computeResourceDirs() {
    synchronized (ITEM_MAP_LOCK) {
      Set<T> result = new HashSet<>();
      for (LocalResourceRepository<T> resourceRepository : myLocalResources) {
        result.addAll(resourceRepository.computeResourceDirs());
      }
      return result;
    }
  }

  @Override
  @NonNull
  public Collection<SingleNamespaceResourceRepository> getLeafResourceRepositories() {
    synchronized (ITEM_MAP_LOCK) {
      return myLeafsByNamespace.values();
    }
  }

  @VisibleForTesting
  @Override
  public int getFileRescans() {
    synchronized (ITEM_MAP_LOCK) {
      int count = 0;
      for (LocalResourceRepository<T> resourceRepository : myLocalResources) {
        count += resourceRepository.getFileRescans();
      }
      return count;
    }
  }

  private static class ResourcePriorityComparator implements Comparator<ResourceItem> {
    private final Object2IntMap<SingleNamespaceResourceRepository> repositoryOrdering;

    ResourcePriorityComparator(@NonNull Collection<SingleNamespaceResourceRepository> repositories) {
      repositoryOrdering = new Object2IntOpenHashMap<>(repositories.size());
      int i = 0;
      for (SingleNamespaceResourceRepository repository : repositories) {
        repositoryOrdering.put(repository, i++);
      }
    }

    @Override
    public int compare(@NonNull ResourceItem item1, @NonNull ResourceItem item2) {
      return Integer.compare(getOrdering(item1), getOrdering(item2));
    }

    private int getOrdering(@NonNull ResourceItem item) {
      int ordering = repositoryOrdering.getInt(item.getRepository());
      assert ordering >= 0;
      return ordering;
    }
  }

  /**
   * Custom implementation of {@link ListMultimap} that may store multiple resource items for
   * the same folder configuration, but for readers exposes ot most one resource item per folder
   * configuration.
   *
   * <p>This ListMultimap implementation is not as robust as Guava multimaps but is sufficient
   * for MultiResourceRepository because the latter always copies data to immutable containers
   * before exposing it to callers.
   */
  private static class PerConfigResourceMap implements ListMultimap<String, ResourceItem> {
    private final Map<String, List<ResourceItem>> myMap = new LinkedHashMap<>();
    private int mySize = 0;
    @NonNull private final ResourceItemComparator myComparator;
    @Nullable private Values myValues;

    private PerConfigResourceMap(@NonNull ResourceItemComparator comparator) {
      myComparator = comparator;
    }

    @Override
    public @NonNull List<ResourceItem> get(@Nullable String key) {
      List<ResourceItem> items = myMap.get(key);
      return items == null ? ImmutableList.of() : items;
    }

    @Override
    @NonNull
    public Set<String> keySet() {
      return myMap.keySet();
    }

    @Override
    @NonNull
    public Multiset<String> keys() {
      throw new UnsupportedOperationException();
    }

    @Override
    @NonNull
    public Collection<ResourceItem> values() {
      Values values = myValues;
      if (values == null) {
        values = new Values();
        myValues = values;
      }
      return values;
    }

    @Override
    @NonNull
    public Collection<Map.Entry<String, ResourceItem>> entries() {
      throw new UnsupportedOperationException();
    }

    @Override
    public @NonNull List<ResourceItem> removeAll(@Nullable Object key) {
      //noinspection SuspiciousMethodCalls
      List<ResourceItem> removed = myMap.remove(key);
      if (removed != null) {
        mySize -= removed.size();
      }
      return removed == null ? ImmutableList.of() : removed;
    }

    @SuppressWarnings("UnusedReturnValue")
    boolean removeIf(@NonNull String key, @NonNull Predicate<? super ResourceItem> filter) {
      List<ResourceItem> list = myMap.get(key);
      if (list == null) {
        return false;
      }
      int oldSize = list.size();
      boolean removed = list.removeIf(filter);
      mySize += list.size() - oldSize;
      if (list.isEmpty()) {
        myMap.remove(key);
      }
      return removed;
    }

    @Override
    public void clear() {
      myMap.clear();
      mySize = 0;
    }

    @Override
    public int size() {
      return mySize;
    }

    @Override
    public boolean isEmpty() {
      return mySize == 0;
    }

    @Override
    public boolean containsKey(@Nullable Object key) {
      //noinspection SuspiciousMethodCalls
      return myMap.containsKey(key);
    }

    @Override
    public boolean containsValue(@Nullable Object value) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean containsEntry(@Nullable Object key, @Nullable Object value) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean put(@NonNull String key, @NonNull ResourceItem item) {
      List<ResourceItem> list = myMap.computeIfAbsent(key, k -> new PerConfigResourceList());
      int oldSize = list.size();
      list.add(item);
      mySize += list.size() - oldSize;
      return true;
    }

    @Override
    public boolean remove(@Nullable Object key, @Nullable Object value) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean putAll(@NonNull String key, @NonNull Iterable<? extends ResourceItem> items) {
      if (items instanceof Collection) {
        if (((Collection<?>)items).isEmpty()) {
          return false;
        }
        List<ResourceItem> list = myMap.computeIfAbsent(key, k -> new PerConfigResourceList());
        int oldSize = list.size();
        //noinspection unchecked
        boolean added = list.addAll((Collection<? extends ResourceItem>)items);
        mySize += list.size() - oldSize;
        return added;
      }

      boolean added = false;
      List<ResourceItem> list = null;
      int oldSize = 0;
      for (ResourceItem item : items) {
        if (list == null) {
          list = myMap.computeIfAbsent(key, k -> new PerConfigResourceList());
          oldSize = list.size();
        }
        added = list.add(item);
      }
      if (list != null) {
        mySize += list.size() - oldSize;
      }
      return added;
    }

    @Override
    public boolean putAll(Multimap<? extends String, ? extends ResourceItem> multimap) {
      for (Map.Entry<? extends String, ? extends Collection<? extends ResourceItem>> entry : multimap.asMap().entrySet()) {
        String key = entry.getKey();
        Collection<? extends ResourceItem> items = entry.getValue();
        if (!items.isEmpty()) {
          List<ResourceItem> list = myMap.computeIfAbsent(key, k -> new PerConfigResourceList());
          int oldSize = list.size();
          list.addAll(items);
          mySize += list.size() - oldSize;
        }
      }

      return !multimap.isEmpty();
    }

    @Override
    public @NonNull List<ResourceItem> replaceValues(@Nullable String key, @NonNull Iterable<? extends ResourceItem> values) {
      throw new UnsupportedOperationException();
    }

    @Override
    public @NonNull Map<String, Collection<ResourceItem>> asMap() {
      //noinspection unchecked
      return (Map<String, Collection<ResourceItem>>)(Map<String, ?>)myMap;
    }

    /**
     * This class has a split personality. The class may store multiple resource items for the same
     * folder configuration, but for callers of non-mutating methods ({@link #get(int)},
     * {@link #size()}, {@link Iterator#next()}, etc) it exposes at most one resource item per
     * folder configuration. Which of the resource items with the same folder configuration is
     * visible to non-mutating methods is determined by {@link ResourcePriorityComparator}.
     */
    private class PerConfigResourceList extends AbstractList<ResourceItem> {
      /** Resource items sorted by folder configurations. Nested lists are sorted by repository priority. */
      private final List<List<ResourceItem>> myResourceItems = new ArrayList<>();

      @Override
      @NonNull
      public ResourceItem get(int index) {
        return myResourceItems.get(index).get(0);
      }

      @Override
      public int size() {
        return myResourceItems.size();
      }

      @Override
      public boolean add(@NonNull ResourceItem item) {
        add(item, 0);
        return true;
      }

      @Override
      public boolean addAll(@NonNull Collection<? extends ResourceItem> items) {
        if (items.isEmpty()) {
          return false;
        }
        if (items.size() == 1) {
          return add(items.iterator().next());
        }

        List<ResourceItem> sortedItems = sortedItems(items);
        int start = 0;
        for (ResourceItem item : sortedItems) {
          start = add(item, start);
        }
        return true;
      }

      private int add(ResourceItem item, int start) {
        int index = findConfigIndex(item, start, myResourceItems.size());
        if (index < 0) {
          index = ~index;
          myResourceItems.add(index, new SmartList<>(item));
        }
        else {
          List<ResourceItem> nested = myResourceItems.get(index);
          // Iterate backwards since it is likely to require fewer iterations.
          int i = nested.size();
          while (--i >= 0) {
            if (myComparator.myPriorityComparator.compare(item, nested.get(i)) > 0) {
              break;
            }
          }
          nested.add(i + 1, item);
        }
        return index;
      }

      @Override
      public void clear() {
        myResourceItems.clear();
      }

      @Override
      public boolean remove(@Nullable Object item) {
        assert item != null;
        int index = remove((ResourceItem)item, myResourceItems.size());
        return index >= 0;
      }

      @Override
      public boolean removeAll(@NonNull Collection<?> items) {
        if (items.isEmpty()) {
          return false;
        }
        if (items.size() == 1) {
          return remove(items.iterator().next());
        }

        @SuppressWarnings("unchecked")
        List<ResourceItem> itemsToDelete = sortedItems((Collection<? extends ResourceItem>)items);
        boolean modified = false;
        int end = myResourceItems.size();
        for (int i = itemsToDelete.size(); --i >= 0;) {
          int index = remove(itemsToDelete.get(i), end);
          if (index > 0) {
            modified = true;
            end = index;
          }
          else {
            end = ~index;
          }
        }
        return modified;
      }

      @Override
      public boolean removeIf(@NonNull Predicate<? super ResourceItem> filter) {
        boolean removed = false;
        for (int i = myResourceItems.size(); --i >= 0;) {
          List<ResourceItem> nested = myResourceItems.get(i);
          for (int j = nested.size(); --j >= 0;) {
            ResourceItem item = nested.get(j);
            if (filter.test(item)) {
              nested.remove(j);
              removed = true;
            }
          }
          if (nested.isEmpty()) {
            myResourceItems.remove(i);
          }
        }
        return removed;
      }

      /**
       * Removes the given resource item from the first {@code end} elements of {@link #myResourceItems}.
       *
       * @param item the resource item to remove
       * @param end the exclusive end of the range checked for existence of the item being deleted
       * @return if the item to be deleted was found, returns its index, otherwise returns
       *     the binary complement of the index pointing to where the item would be inserted
       */
      private int remove(@NonNull ResourceItem item, int end) {
        int index = findConfigIndex(item, 0, end);
        if (index < 0) {
          return index;
        }

        List<ResourceItem> nested = myResourceItems.get(index);
        if (!nested.remove(item)) {
          return ~(index + 1);
        }

        if (nested.isEmpty()) {
          myResourceItems.remove(index);
          return index;
        }
        return index + 1;
      }

      @NonNull
      private List<ResourceItem> sortedItems(@NonNull Collection<? extends ResourceItem> items) {
        List<ResourceItem> sortedItems = new ArrayList<>(items);
        sortedItems.sort(myComparator);
        return sortedItems;
      }

      /**
       * Returns index in {@link #myResourceItems} of the existing resource item with the same
       * configuration as the {@code item} parameter. If {@link #myResourceItems} doesn't contains
       * resources with the same configuration, returns binary complement of the insertion point.
       */
      private int findConfigIndex(@NonNull ResourceItem item, int start, int end) {
        FolderConfiguration config = item.getConfiguration();
        int low = start;
        int high = end;

        while (low < high) {
          int mid = (low + high) >>> 1;
          FolderConfiguration value = myResourceItems.get(mid).get(0).getConfiguration();
          int c = value.compareTo(config);
          if (c < 0) {
            low = mid + 1;
          }
          else if (c > 0) {
            high = mid;
          }
          else {
            return mid;
          }
        }
        return ~low; // Not found.
      }
    }

    private class Values extends AbstractCollection<ResourceItem> {
      @Override
      public @NonNull Iterator<ResourceItem> iterator() {
        return new ValuesIterator();
      }

      @Override
      public int size() {
        return mySize;
      }

      private class ValuesIterator implements Iterator<ResourceItem> {
        private final Iterator<List<ResourceItem>> myOuterCursor = myMap.values().iterator();
        private List<ResourceItem> myCurrentList;
        private int myInnerCursor;

        @Override
        public boolean hasNext() {
          return myCurrentList != null || myOuterCursor.hasNext();
        }

        @Override
        public ResourceItem next() {
          if (myCurrentList == null) {
            myCurrentList = myOuterCursor.next();
            myInnerCursor = 0;
          }
          try {
            ResourceItem item = myCurrentList.get(myInnerCursor);
            if (++myInnerCursor >= myCurrentList.size()) {
              myCurrentList = null;
            }
            return item;
          }
          catch (IndexOutOfBoundsException e) {
            throw new NoSuchElementException();
          }
        }
      }
    }
  }

  private static class ResourceItemComparator implements Comparator<ResourceItem> {
    private final Comparator<ResourceItem> myPriorityComparator;

    ResourceItemComparator(Comparator<ResourceItem> priorityComparator) {
      myPriorityComparator = priorityComparator;
    }

    @Override
    public int compare(@NonNull ResourceItem item1, @NonNull ResourceItem item2) {
      int c = item1.getConfiguration().compareTo(item2.getConfiguration());
      if (c != 0) {
        return c;
      }
      return myPriorityComparator.compare(item1, item2);
    }
  }
}
