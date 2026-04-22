import * as DialogPrimitive from "@radix-ui/react-dialog";
import { X } from "lucide-react";
import type * as React from "react";
import { cn } from "@/lib/utils";

/** Dialog 根组件，负责维护弹窗打开状态。 */
export const Dialog = DialogPrimitive.Root;

/** Dialog 触发器组件，用于登录按钮打开表单。 */
export const DialogTrigger = DialogPrimitive.Trigger;

/** Dialog Portal 组件，确保弹窗渲染到顶层。 */
export const DialogPortal = DialogPrimitive.Portal;

/** Dialog 关闭组件，用于右上角关闭按钮。 */
export const DialogClose = DialogPrimitive.Close;

/** Dialog 遮罩层，隔离底部主界面交互。 */
export function DialogOverlay({ className, ...props }: React.ComponentProps<typeof DialogPrimitive.Overlay>) {
  return (
    <DialogPrimitive.Overlay
      className={cn("fixed inset-0 z-50 bg-black/45 backdrop-blur-sm", className)}
      {...props}
    />
  );
}

/** Dialog 内容容器，使用 Shadcn 风格的居中浮层。 */
export function DialogContent({ className, children, ...props }: React.ComponentProps<typeof DialogPrimitive.Content>) {
  return (
    <DialogPortal>
      <DialogOverlay />
      <DialogPrimitive.Content
        className={cn(
          "fixed left-1/2 top-1/2 z-50 grid w-[calc(100%-2rem)] max-w-md -translate-x-1/2 -translate-y-1/2 gap-4 rounded-lg border bg-popover p-6 text-popover-foreground shadow-xl",
          className,
        )}
        {...props}
      >
        {children}
        <DialogPrimitive.Close className="absolute right-4 top-4 rounded-sm opacity-70 transition-opacity hover:opacity-100 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring">
          <X className="size-4" />
          <span className="sr-only">关闭</span>
        </DialogPrimitive.Close>
      </DialogPrimitive.Content>
    </DialogPortal>
  );
}

/** Dialog 标题组件。 */
export function DialogTitle({ className, ...props }: React.ComponentProps<typeof DialogPrimitive.Title>) {
  return <DialogPrimitive.Title className={cn("text-lg font-semibold", className)} {...props} />;
}

/** Dialog 描述组件。 */
export function DialogDescription({ className, ...props }: React.ComponentProps<typeof DialogPrimitive.Description>) {
  return <DialogPrimitive.Description className={cn("text-sm text-muted-foreground", className)} {...props} />;
}
