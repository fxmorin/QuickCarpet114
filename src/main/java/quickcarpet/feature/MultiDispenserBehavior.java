package quickcarpet.feature;

import net.minecraft.block.dispenser.DispenserBehavior;
import net.minecraft.block.dispenser.FallibleItemDispenserBehavior;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPointer;
import quickcarpet.mixin.accessor.FallibleItemDispenserBehaviorAccessor;
import quickcarpet.mixin.accessor.ItemDispenserBehaviorAccessor;

public class MultiDispenserBehavior extends FallibleItemDispenserBehavior {
    private DispenserBehavior[] behaviors;

    public MultiDispenserBehavior(DispenserBehavior...behaviors) {
        this.behaviors = behaviors;
    }

    @Override
    protected ItemStack dispenseSilently(BlockPointer pointer, ItemStack stackIn) {
        for (DispenserBehavior behavior : behaviors) {
            ItemStack stackOut = ((ItemDispenserBehaviorAccessor) behavior).doDispenseSilently(pointer, stackIn);
            if (!(behavior instanceof FallibleItemDispenserBehavior) || ((FallibleItemDispenserBehaviorAccessor) behavior).isSuccessful()) {
                this.setSuccess(true);
                return stackOut;
            }
        }
        return stackIn;
    }
}
