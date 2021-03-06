package com.tradehero.route;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import com.tradehero.route.internal.RouterProcessor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;

public class Router {
  private static final String TAG = "Router";
  private static boolean debug = true;
  protected final Map<String, RouterOptions> routes = new HashMap<String, RouterOptions>();
  protected final Map<String, RouterParams> cachedRoutes = new HashMap<String, RouterParams>();

  private Context context;

  /**
   * Creates a new Router
   */
  public Router() {
  }

  private static final Router router = new Router();

  /**
   * A globally accessible Router instance that will work for
   * most use cases.
   */
  public static Router getInstance() {
    return router;
  }

  /**
   * Creates a new Router
   *
   * @param context {@link android.content.Context} that all {@link android.content.Intent}s
   * generated by the router will use
   */
  public Router(Context context) {
    this.setContext(context);
  }

  /**
   * @param context {@link android.content.Context} that all {@link android.content.Intent}s
   * generated by the router will use
   */
  public void setContext(Context context) {
    this.context = context.getApplicationContext();
  }


  /**
   * Map a URL to open an {@link android.app.Activity}
   *
   * @param format The URL being mapped; for example, "users/:id" or "groups/:id/topics/:topic_id"
   * @param klass The {@link android.app.Activity} class to be opened with the URL
   */
  public void map(String format, Class<? extends Activity> klass) {
    this.map(format, klass, null);
  }

  /**
   * Map a URL to open an {@link android.app.Activity}
   *
   * @param format The URL being mapped; for example, "users/:id" or "groups/:id/topics/:topic_id"
   * @param klass The {@link android.app.Activity} class to be opened with the URL
   * @param options The {@link RouterOptions} to be used for more granular and customized options
   * for when the URL is opened
   */
  public void map(String format, Class<? extends Activity> klass, RouterOptions options) {
    if (options == null) {
      options = new RouterOptions();
    }
    options.setOpenClass(klass);
    this.routes.put(format, options);
  }

  /**
   * @param url The URL; for example, "users/16" or "groups/5/topics/20"
   */
  public void open(String url) {
    this.open(url, this.context);
  }

  /**
   *
   * @param url The URL; for example, "users/16" or "groups/5/topics/20"
   * @param context The context which is used in the generated {@link android.content.Intent}
   */
  public void open(String url, Context context) {
    if (context == null) {
      throw new RuntimeException("You need to supply a context for Router " + this.toString());
    }
    RouterParams params = this.paramsForUrl(url);
    RouterOptions options = params.routerOptions;
    if (options.getCallback() != null) {
      options.getCallback().run(params.openParams);
      return;
    }

    Intent intent = this.intentFor(context, url);
    if (intent == null) {
      // Means the options weren't opening a new activity
      return;
    }
    context.startActivity(intent);
  }

  /*
   * Allows Intents to be spawned regardless of what context they were opened with.
   */
  private void addFlagsToIntent(Intent intent, Context context) {
    if (context == this.context) {
      intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }
  }

  /**
   * @param url The URL; for example, "users/16" or "groups/5/topics/20"
   * @return The {@link android.content.Intent} for the url
   */
  public Intent intentFor(String url) {
    RouterParams params = this.paramsForUrl(url);
    RouterOptions options = params.routerOptions;
    Intent intent = new Intent();
    if (options.getDefaultParams() != null) {
      for (Entry<String, String> entry : options.getDefaultParams().entrySet()) {
        intent.putExtra(entry.getKey(), entry.getValue());
      }
    }
    for (Entry<String, String> entry : params.openParams.entrySet()) {
      intent.putExtra(entry.getKey(), entry.getValue());
    }
    return intent;
  }

  /**
   * @param url The URL to check
   * @return Whether or not the URL refers to an anonymous callback function
   */
  public boolean isCallbackUrl(String url) {
    RouterParams params = this.paramsForUrl(url);
    RouterOptions options = params.routerOptions;
    return options.getCallback() != null;
  }

  /**
   * @param context The context which is spawning the intent
   * @param url The URL; for example, "users/16" or "groups/5/topics/20"
   * @return The {@link android.content.Intent} for the url, with the correct {@link
   * android.app.Activity} set, or null.
   */
  public Intent intentFor(Context context, String url) {
    RouterParams params = this.paramsForUrl(url);
    RouterOptions options = params.routerOptions;
    if (options.getCallback() != null) {
      return null;
    }

    Intent intent = intentFor(url);
    intent.setClass(context, options.getOpenClass());
    this.addFlagsToIntent(intent, context);
    return intent;
  }

  /*
   * Takes a url (i.e. "/users/16/hello") and breaks it into a {@link RouterParams} instance where
   * each of the parameters (like ":id") has been parsed.
   */
  protected RouterParams paramsForUrl(String url) {
    if (this.cachedRoutes.get(url) != null) {
      return this.cachedRoutes.get(url);
    }

    String[] givenParts = url.split("/");

    RouterOptions openOptions = null;
    RouterParams openParams = null;
    for (Entry<String, RouterOptions> entry : this.routes.entrySet()) {
      String routerUrl = entry.getKey();
      RouterOptions routerOptions = entry.getValue();
      String[] routerParts = routerUrl.split("/");

      if (routerParts.length != givenParts.length) {
        continue;
      }

      Map<String, String> givenParams = urlToParamsMap(givenParts, routerParts);
      if (givenParams == null) {
        continue;
      }

      openOptions = routerOptions;
      openParams = new RouterParams();
      openParams.openParams = givenParams;
      openParams.routerOptions = routerOptions;
      break;
    }

    if (openOptions == null) {
      throw new RouteNotFoundException("No route found for url " + url);
    }

    this.cachedRoutes.put(url, openParams);
    return openParams;
  }

  /**
   * @param givenUrlSegments An array representing the URL path attempting to be opened (i.e.
   * ["users", "42"])
   * @param routerUrlSegments An array representing a possible URL match for the router (i.e.
   * ["users", ":id"])
   * @return A map of URL parameters if it's a match (i.e. {"id" => "42"}) or null if there is no
   * match
   */
  private Map<String, String> urlToParamsMap(String[] givenUrlSegments, String[] routerUrlSegments) {
    Map<String, String> formatParams = new HashMap<String, String>();
    for (int index = 0; index < routerUrlSegments.length; index++) {
      String routerPart = routerUrlSegments[index];
      String givenPart = givenUrlSegments[index];

      if (routerPart.charAt(0) == ':') {
        String key = routerPart.substring(1, routerPart.length());
        formatParams.put(key, givenPart);
        continue;
      }

      if (!routerPart.equals(givenPart)) {
        return null;
      }
    }

    return formatParams;
  }

  static final Map<Class<?>, Method> INJECTORS = new LinkedHashMap<Class<?>, Method>();
  static final Map<Class<?>, Method> SAVERS = new LinkedHashMap<Class<?>, Method>();

  static final Method NO_OP = null;

  public void inject(Activity activity) {
    Bundle extras = activity.getIntent() != null ? activity.getIntent().getExtras() : null;
    inject(activity, extras);
  }

  public void inject(Object target, Bundle extras) {
    Class<?> targetClass = target.getClass();
    try {
      if (debug) Log.d(TAG, "Looking up injector for " + targetClass.getName());
      Method inject = findInjectorForClass(targetClass);
      if (inject != null) {
        inject.invoke(null, target, extras);
      }
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      Throwable t = e;
      if (t instanceof InvocationTargetException) {
        t = t.getCause();
      }
      throw new RuntimeException("Unable to inject for " + target, t);
    }
  }

  /**
   * Save POJO to bundle with hierarchy
   * @param extras
   * @param parcelables
   */
  public void save(Bundle extras, Object... parcelables) {
    if (parcelables == null) return;

    boolean flat = false;
    int length = parcelables.length;

    if (length > 1 && parcelables[length - 1] instanceof Boolean) {
      flat = (Boolean) parcelables[length - 1];
      --length;
    }

    for (int i = 0; i< length;  ++i) {
      saveSingle(extras, parcelables[i], flat);
    }
  }

  /**
   * Save POJO to bundle without hierarchy
   * @param extras
   * @param parcelables
   */
  public void saveFlat(Bundle extras, Object... parcelables) {
    for (Object parcelable: parcelables) {
      saveSingle(extras, parcelable, true);
    }
  }

  public void saveSingle(Bundle extras, Object parcelable, boolean flat) {
    Class<?> targetClass = parcelable.getClass();
    try {
      if (debug) Log.d(TAG, "Looking up saver for " + targetClass.getName());
      Method inject = findSaverForClass(targetClass);
      if (inject != null) {
        inject.invoke(null, parcelable, extras, flat);
      }
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      Throwable t = e;
      if (t instanceof InvocationTargetException) {
        t = t.getCause();
      }
      throw new RuntimeException("Unable to inject for " + parcelable, t);
    }
  }

  private static Method findInjectorForClass(Class<?> cls) throws NoSuchMethodException {
    Method inject = INJECTORS.get(cls);
    if (inject != null) {
      if (debug) Log.d(TAG, "HIT: Cached in injector map.");
      return inject;
    }
    String clsName = cls.getName();
    if (clsName.startsWith("android.") || clsName.startsWith("java.")) {
      if (debug) Log.d(TAG, "MISS: Reached framework class. Abandoning search.");
      return NO_OP;
    }
    try {
      Class<?> injector = Class.forName(clsName + RouterProcessor.SUFFIX);
      inject = injector.getMethod("inject", cls, Bundle.class);
      if (debug) Log.d(TAG, "HIT: Class loaded injection class.");
    } catch (ClassNotFoundException e) {
      if (debug) Log.d(TAG, "Not found. Trying superclass " + cls.getSuperclass().getName());
      // TODO: consider parent to be injected too?
      inject = findInjectorForClass(cls.getSuperclass());
    }
    INJECTORS.put(cls, inject);
    return inject;
  }

  private static Method findSaverForClass(Class<?> cls) throws NoSuchMethodException {
    Method inject = SAVERS.get(cls);
    if (inject != null) {
      if (debug) Log.d(TAG, "HIT: Cached in saver map.");
      return inject;
    }
    String clsName = cls.getName();
    if (clsName.startsWith("android.") || clsName.startsWith("java.")) {
      if (debug) Log.d(TAG, "MISS: Reached framework class. Abandoning search.");
      return NO_OP;
    }
    try {
      Class<?> injector = Class.forName(clsName + RouterProcessor.SUFFIX);
      inject = injector.getMethod("save", cls, Bundle.class, boolean.class);
      if (debug) Log.d(TAG, "HIT: Class loaded injection class.");
    } catch (ClassNotFoundException e) {
      if (debug) Log.d(TAG, "Not found. Trying superclass " + cls.getSuperclass().getName());
    }
    SAVERS.put(cls, inject);
    return inject;
  }

  public Router registerRoutes(Class<?>... targets) {
    for (Class<?> target: targets) {
      if (Activity.class.isAssignableFrom(target) && target.isAnnotationPresent(Routable.class)) {
        Routable routable = target.getAnnotation(Routable.class);
        if (debug) Log.d(TAG, routable.toString());

        String[] routes = routable.value();
        if (routes != null) {
          for (String route: routes) {
            @SuppressWarnings("unchecked")
            Class<? extends Activity> activityTarget = (Class<? extends Activity>) target;
            map(route, activityTarget);
          }
        }
      }
    }
    return this;
  }
}
