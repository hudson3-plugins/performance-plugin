performance-plugin
==================

This Hudson plugin allows you to see how your tests perform over time.

The plugin is capable to notify regression in performance (ms and percentage).
For example you could configure a build to fail when::

    Errors count > 3 OR (Performance Regression > 15% AND Performance Regression Time > 2000ms)
