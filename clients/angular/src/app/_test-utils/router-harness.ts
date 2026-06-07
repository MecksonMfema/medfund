import { Router, UrlTree } from '@angular/router';

/**
 * Minimal Router double for guards / components that only call
 * {@code router.navigate} or {@code router.createUrlTree}. Records every call
 * so tests can assert routing decisions without spinning a real RouterModule.
 */
export class RouterHarness {
  navigateCalls: ReadonlyArray<unknown>[] = [];
  createUrlTreeCalls: ReadonlyArray<unknown>[] = [];

  navigate = async (commands: ReadonlyArray<unknown>): Promise<boolean> => {
    this.navigateCalls.push(commands);
    return true;
  };

  createUrlTree = (commands: ReadonlyArray<unknown>): UrlTree => {
    this.createUrlTreeCalls.push(commands);
    // Fake UrlTree — sufficient for guards that only check the return value
    // shape. Tests should assert on createUrlTreeCalls, not on the tree itself.
    return { __commands: commands } as unknown as UrlTree;
  };

  asRouter(): Router {
    return this as unknown as Router;
  }
}

export function provideRouterHarness(harness: RouterHarness = new RouterHarness()) {
  return { provide: Router, useValue: harness };
}
